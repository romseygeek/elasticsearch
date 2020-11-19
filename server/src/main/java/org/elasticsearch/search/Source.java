/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.util.Collections;
import java.util.Map;

/**
 * Mediates access to a document's source map
 */
public interface Source {

    Source EMPTY_SOURCE = new Source() {
        @Override
        public Map<String, Object> source() {
            return Collections.emptyMap();
        }

        @Override
        public BytesReference internalSourceRef() {
            return null;
        }

        @Override
        public XContentType sourceContentType() {
            return null;
        }
    };

    // TODO used in upserts, would be nice to remove this entirely
    static Source fromMap(Map<String, Object> map, XContentType xContentType) {
        return new Source() {
            @Override
            public Map<String, Object> source() {
                return map;
            }

            @Override
            public BytesReference internalSourceRef() {
                return null;
            }

            @Override
            public XContentType sourceContentType() {
                return xContentType;
            }
        };
    }

    static Source fromBytes(BytesReference bytes) {
        return new Source() {
            Map<String, Object> parsedSource = null;
            XContentType xContentType;

            @Override
            public Map<String, Object> source() {
                if (parsedSource == null) {
                    // TODO can we be cleverer here and not need to use XContentType? Only used in FetchSourcePhase
                    Tuple<XContentType, Map<String, Object>> tuple = XContentHelper.convertToMap(bytes, false);
                    parsedSource = tuple.v2();
                    xContentType = tuple.v1();
                }
                return parsedSource;
            }

            @Override
            public BytesReference internalSourceRef() {
                return bytes;
            }

            @Override
            public XContentType sourceContentType() {
                source();   // parse if need be
                return xContentType;
            }
        };
    }

    /**
     * The source represented as a map of maps
     */
    Map<String, Object> source();

    /**
     * The source represented as a BytesReference
     */
    BytesReference internalSourceRef();

    /**
     * The XContentType of the source
     */
    XContentType sourceContentType();

    // TODO make this return a BytesReference instead and merge with internalSourceRef()
    default Object filter(FetchSourceContext context) {
        return context.getFilter().apply(source());
    }

}
