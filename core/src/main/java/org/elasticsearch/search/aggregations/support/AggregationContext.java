/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.support;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexGeoPointFieldData;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.fielddata.IndexOrdinalsFieldData;
import org.elasticsearch.index.fielddata.plain.ParentChildIndexFieldData;
import org.elasticsearch.search.SearchParseException;
import org.elasticsearch.search.aggregations.AggregationExecutionException;
import org.elasticsearch.search.internal.SearchContext;
import org.joda.time.DateTimeZone;

import java.io.IOException;

public class AggregationContext {

    private final SearchContext searchContext;

    public AggregationContext(SearchContext searchContext) {
        this.searchContext = searchContext;
    }

    public SearchContext searchContext() {
        return searchContext;
    }

    public BigArrays bigArrays() {
        return searchContext.bigArrays();
    }

    /** Get a value source given its configuration. A return value of null indicates that
     *  no value source could be built. */
    @Nullable
    public <VS extends ValuesSource> VS valuesSource(ValuesSourceConfig<VS> config, SearchContext context) throws IOException {
        if (!config.valid()) {
            throw new IllegalStateException(
                    "value source config is invalid; must have either a field context or a script or marked as unwrapped");
        }

        final VS vs;
        if (config.unmapped()) {
            if (config.missing() == null) {
                // otherwise we will have values because of the missing value
                vs = null;
            } else if (config.valueSourceType() == ValuesSourceType.NUMERIC) {
                vs = (VS) ValuesSource.Numeric.EMPTY;
            } else if (config.valueSourceType() == ValuesSourceType.GEOPOINT) {
                vs = (VS) ValuesSource.GeoPoint.EMPTY;
            } else if (config.valueSourceType() == ValuesSourceType.ANY || config.valueSourceType() == ValuesSourceType.BYTES) {
                vs = (VS) ValuesSource.Bytes.WithOrdinals.EMPTY;
            } else {
                throw new SearchParseException(searchContext, "Can't deal with unmapped ValuesSource type "
                    + config.valueSourceType(), null);
            }
        } else {
            vs = originalValuesSource(config);
        }

        if (config.missing() == null) {
            return vs;
        }

        if (vs instanceof ValuesSource.Bytes) {
            final BytesRef missing = new BytesRef(config.missing().toString());
            if (vs instanceof ValuesSource.Bytes.WithOrdinals) {
                return (VS) MissingValues.replaceMissing((ValuesSource.Bytes.WithOrdinals) vs, missing);
            } else {
                return (VS) MissingValues.replaceMissing((ValuesSource.Bytes) vs, missing);
            }
        } else if (vs instanceof ValuesSource.Numeric) {
            Number missing = null;
            if (config.missing() instanceof Number) {
                missing = (Number) config.missing();
            } else {
                if (config.fieldContext() != null && config.fieldContext().fieldType() != null) {
                    missing = config.fieldContext().fieldType().docValueFormat(null, DateTimeZone.UTC)
                            .parseDouble(config.missing().toString(), false, context.nowCallable());
                } else {
                    missing = Double.parseDouble(config.missing().toString());
                }
            }
            return (VS) MissingValues.replaceMissing((ValuesSource.Numeric) vs, missing);
        } else if (vs instanceof ValuesSource.GeoPoint) {
            // TODO: also support the structured formats of geo points
            final GeoPoint missing = GeoUtils.parseGeoPoint(config.missing().toString(), new GeoPoint());
            return (VS) MissingValues.replaceMissing((ValuesSource.GeoPoint) vs, missing);
        } else {
            // Should not happen
            throw new SearchParseException(searchContext, "Can't apply missing values on a " + vs.getClass(), null);
        }
    }

    /**
     * Return the original values source, before we apply `missing`.
     */
    private <VS extends ValuesSource> VS originalValuesSource(ValuesSourceConfig<VS> config) throws IOException {
        if (config.fieldContext() == null) {
            if (config.valueSourceType() == ValuesSourceType.NUMERIC) {
                return (VS) numericScript(config);
            }
            if (config.valueSourceType() == ValuesSourceType.BYTES) {
                return (VS) bytesScript(config);
            }
            throw new AggregationExecutionException("value source of type [" + config.valueSourceType().name()
                    + "] is not supported by scripts");
        }

        if (config.valueSourceType() == ValuesSourceType.NUMERIC) {
            return (VS) numericField(config);
        }
        if (config.valueSourceType() == ValuesSourceType.GEOPOINT) {
            return (VS) geoPointField(config);
        }
        // falling back to bytes values
        return (VS) bytesField(config);
    }

    private ValuesSource.Numeric numericScript(ValuesSourceConfig<?> config) throws IOException {
        return new ValuesSource.Numeric.Script(config.script(), config.scriptValueType());
    }

    private ValuesSource.Numeric numericField(ValuesSourceConfig<?> config) throws IOException {

        if (!(config.fieldContext().indexFieldData() instanceof IndexNumericFieldData)) {
            throw new IllegalArgumentException("Expected numeric type on field [" + config.fieldContext().field() +
                    "], but got [" + config.fieldContext().fieldType().typeName() + "]");
        }

        ValuesSource.Numeric dataSource = new ValuesSource.Numeric.FieldData((IndexNumericFieldData)config.fieldContext().indexFieldData());
        if (config.script() != null) {
            dataSource = new ValuesSource.Numeric.WithScript(dataSource, config.script());
        }
        return dataSource;
    }

    private ValuesSource bytesField(ValuesSourceConfig<?> config) throws IOException {
        final IndexFieldData<?> indexFieldData = config.fieldContext().indexFieldData();
        ValuesSource dataSource;
        if (indexFieldData instanceof ParentChildIndexFieldData) {
            dataSource = new ValuesSource.Bytes.WithOrdinals.ParentChild((ParentChildIndexFieldData) indexFieldData);
        } else if (indexFieldData instanceof IndexOrdinalsFieldData) {
            dataSource = new ValuesSource.Bytes.WithOrdinals.FieldData((IndexOrdinalsFieldData) indexFieldData);
        } else {
            dataSource = new ValuesSource.Bytes.FieldData(indexFieldData);
        }
        if (config.script() != null) {
            dataSource = new ValuesSource.WithScript(dataSource, config.script());
        }
        return dataSource;
    }

    private ValuesSource.Bytes bytesScript(ValuesSourceConfig<?> config) throws IOException {
        return new ValuesSource.Bytes.Script(config.script());
    }

    private ValuesSource.GeoPoint geoPointField(ValuesSourceConfig<?> config) throws IOException {

        if (!(config.fieldContext().indexFieldData() instanceof IndexGeoPointFieldData)) {
            throw new IllegalArgumentException("Expected geo_point type on field [" + config.fieldContext().field() +
                    "], but got [" + config.fieldContext().fieldType().typeName() + "]");
        }

        return new ValuesSource.GeoPoint.Fielddata((IndexGeoPointFieldData) config.fieldContext().indexFieldData());
    }

}
