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
package org.elasticsearch.search.lookup;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.CheckedBiConsumer;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.lucene.index.SequentialStoredFieldsLeafReader;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.fieldvisitor.FieldsVisitor;
import org.elasticsearch.search.Source;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyMap;

public class SourceLookup implements Map<String, Object>, Source {

    private LeafReader reader;
    CheckedBiConsumer<Integer, FieldsVisitor, IOException> fieldReader;

    private int docId = -1;

    private BytesReference sourceAsBytes;
    private Map<String, Object> source;
    private XContentType sourceContentType;

    @Override
    public Map<String, Object> source() {
        return loadSourceIfNeeded();
    }

    public XContentType sourceContentType() {
        return sourceContentType;
    }

    // Scripting requires this method to be public. Using source()
    // is not possible because certain checks use source == null as
    // as a determination if source is enabled/disabled, but it should
    // never be a null Map for scripting even when disabled.
    public Map<String, Object> loadSourceIfNeeded() {
        if (source != null) {
            return source;
        }
        if (sourceAsBytes != null) {
            Tuple<XContentType, Map<String, Object>> tuple = sourceAsMapAndType(sourceAsBytes);
            sourceContentType = tuple.v1();
            source = tuple.v2();
            return source;
        }
        try {
            FieldsVisitor sourceFieldVisitor = new FieldsVisitor(true);
            fieldReader.accept(docId, sourceFieldVisitor);
            BytesReference source = sourceFieldVisitor.source();
            if (source == null) {
                this.source = emptyMap();
                this.sourceContentType = null;
            } else {
                Tuple<XContentType, Map<String, Object>> tuple = sourceAsMapAndType(source);
                this.sourceContentType = tuple.v1();
                this.source = tuple.v2();
            }
        } catch (Exception e) {
            throw new ElasticsearchParseException("failed to parse / load source", e);
        }
        return this.source;
    }

    private static Tuple<XContentType, Map<String, Object>> sourceAsMapAndType(BytesReference source) throws ElasticsearchParseException {
        return XContentHelper.convertToMap(source, false);
    }

    public static Map<String, Object> sourceAsMap(BytesReference source) throws ElasticsearchParseException {
        return sourceAsMapAndType(source).v2();
    }

    public void setSegmentAndDocument(
        LeafReaderContext context,
        int docId
    ) {
        if (this.reader == context.reader() && this.docId == docId) {
            // if we are called with the same document, don't invalidate source
            return;
        }
        if (this.reader != context.reader()) {
            this.reader = context.reader();
            // only reset reader and fieldReader when reader changes
            try {
                if (context.reader() instanceof SequentialStoredFieldsLeafReader) {
                    // All the docs to fetch are adjacent but Lucene stored fields are optimized
                    // for random access and don't optimize for sequential access - except for merging.
                    // So we do a little hack here and pretend we're going to do merges in order to
                    // get better sequential access.
                    SequentialStoredFieldsLeafReader lf = (SequentialStoredFieldsLeafReader) context.reader();
                    fieldReader = lf.getSequentialStoredFieldsReader()::visitDocument;
                } else {
                    fieldReader = context.reader()::document;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        this.source = null;
        this.sourceAsBytes = null;
        this.docId = docId;
    }

    /**
     * Internal source representation, might be compressed....
     */
    @Override
    public BytesReference internalSourceRef() {
        return sourceAsBytes;
    }

    @Override
    public Object filter(FetchSourceContext context) {
        return context.getFilter().apply(loadSourceIfNeeded());
    }

    @Override
    public Object get(Object key) {
        return loadSourceIfNeeded().get(key);
    }

    @Override
    public int size() {
        return loadSourceIfNeeded().size();
    }

    @Override
    public boolean isEmpty() {
        return loadSourceIfNeeded().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return loadSourceIfNeeded().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return loadSourceIfNeeded().containsValue(value);
    }

    @Override
    public Set<String> keySet() {
        return loadSourceIfNeeded().keySet();
    }

    @Override
    public Collection<Object> values() {
        return loadSourceIfNeeded().values();
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        return loadSourceIfNeeded().entrySet();
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void putAll(Map m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }
}
