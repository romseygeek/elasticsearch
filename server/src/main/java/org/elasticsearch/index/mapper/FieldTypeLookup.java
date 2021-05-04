/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.common.regex.Regex;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable container for looking up {@link MappedFieldType}s by their name.
 */
final class FieldTypeLookup {
    private final Map<String, MappedFieldType> fullNameToFieldType = new HashMap<>();
    private final Map<String, RuntimeField> runtimeFields = new HashMap<>();
    private final Map<String, DynamicFieldType> dynamicFieldTypes = new HashMap<>();

    /**
     * A map from field name to all fields whose content has been copied into it
     * through copy_to. A field only be present in the map if some other field
     * has listed it as a target of copy_to.
     *
     * For convenience, the set of copied fields includes the field itself.
     */
    private final Map<String, Set<String>> fieldToCopiedFields = new HashMap<>();

    private final int maxParentPathDots;

    FieldTypeLookup(
        Collection<FieldMapper> fieldMappers,
        Collection<FieldAliasMapper> fieldAliasMappers,
        Collection<RuntimeField> runtimeFields
    ) {

        for (FieldMapper fieldMapper : fieldMappers) {
            String fieldName = fieldMapper.name();
            MappedFieldType fieldType = fieldMapper.fieldType();
            fullNameToFieldType.put(fieldType.name(), fieldType);
            if (fieldType instanceof DynamicFieldType) {
                dynamicFieldTypes.put(fieldType.name(), (DynamicFieldType) fieldType);
            }
            for (String targetField : fieldMapper.copyTo().copyToFields()) {
                Set<String> sourcePath = fieldToCopiedFields.get(targetField);
                if (sourcePath == null) {
                    Set<String> copiedFields = new HashSet<>();
                    copiedFields.add(targetField);
                    fieldToCopiedFields.put(targetField, copiedFields);
                }
                fieldToCopiedFields.get(targetField).add(fieldName);
            }
        }

        int maxParentPathDots = 0;
        for (String dynamicRoot : dynamicFieldTypes.keySet()) {
            maxParentPathDots = Math.max(maxParentPathDots, dotCount(dynamicRoot));
        }
        this.maxParentPathDots = maxParentPathDots;

        for (FieldAliasMapper fieldAliasMapper : fieldAliasMappers) {
            String aliasName = fieldAliasMapper.name();
            String path = fieldAliasMapper.path();
            this.runtimeFields.put(aliasName, new AliasRuntimeField(aliasName, path));
        }

        for (RuntimeField runtimeField : runtimeFields) {
            this.runtimeFields.put(runtimeField.name(), runtimeField);
        }
    }

    private static int dotCount(String path) {
        int dotCount = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '.') {
                dotCount++;
            }
        }
        return dotCount;
    }

    /**
     * Returns the mapped field type for the given field name.
     */
    MappedFieldType get(String field) {
        GuardedFieldTypeLookup guardedLookup = new GuardedFieldTypeLookup();
        return guardedLookup.get(field);
    }

    private class GuardedFieldTypeLookup {

        Set<String> fieldPath = new LinkedHashSet<>();

        MappedFieldType get(String field) {
            if (fieldPath.contains(field)) {
                throw new IllegalStateException("Loop in field resolution detected: " + String.join("->", fieldPath) + "->" + field);
            }
            fieldPath.add(field);
            RuntimeField runtimeFieldType = FieldTypeLookup.this.runtimeFields.get(field);
            if (runtimeFieldType != null) {
                return runtimeFieldType.asMappedFieldType(this::get);
            }

            MappedFieldType fieldType = fullNameToFieldType.get(field);
            if (fieldType != null) {
                return fieldType;
            }

            // If the mapping contains fields that support dynamic sub-key lookup, check
            // if this could correspond to a keyed field of the form 'path_to_field.path_to_key'.
            return getDynamicField(field);
        }
    }

    // for testing
    int getMaxParentPathDots() {
        return maxParentPathDots;
    }

    // Check if the given field corresponds to a dynamic key mapper of the
    // form 'path_to_field.path_to_key'. If so, returns a field type that
    // can be used to perform searches on this field. Otherwise returns null.
    private MappedFieldType getDynamicField(String field) {
        if (dynamicFieldTypes.isEmpty()) {
            // no parent fields defined
            return null;
        }
        int dotIndex = -1;
        int fieldDepth = -1;

        while (true) {
            if (++fieldDepth > maxParentPathDots) {
                return null;
            }

            dotIndex = field.indexOf('.', dotIndex + 1);
            if (dotIndex < 0) {
                return null;
            }

            String parentField = field.substring(0, dotIndex);
            DynamicFieldType dft = dynamicFieldTypes.get(parentField);
            if (dft != null && Objects.equals(field, parentField) == false) {
                String key = field.substring(dotIndex + 1);
                return dft.getChildFieldType(key);
            }
            if (runtimeFields.containsKey(parentField)) {
                MappedFieldType ft = runtimeFields.get(parentField).asMappedFieldType(this::get);
                if (ft instanceof DynamicFieldType) {
                    String key = field.substring(dotIndex + 1);
                    return ((DynamicFieldType) ft).getChildFieldType(key);
                }
            }
        }
    }

    /**
     * Returns all the mapped field types.
     */
    Collection<MappedFieldType> get() {
        return fullNameToFieldType.values();
    }

    /**
     * Returns a list of the full names of a simple match regex like pattern against full name and index name.
     */
    Set<String> simpleMatchToFullName(String pattern) {
        if (Regex.isSimpleMatchPattern(pattern) == false) {
            // no wildcards
            return Collections.singleton(pattern);
        }
        Set<String> fields = new HashSet<>();
        for (String field : runtimeFields.keySet()) {
            if (Regex.simpleMatch(pattern, field)) {
                fields.add(field);
            }
        }
        for (String field : fullNameToFieldType.keySet()) {
            if (Regex.simpleMatch(pattern, field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Given a concrete field name, return its paths in the _source.
     *
     * For most fields, the source path is the same as the field itself. However
     * there are cases where a field's values are found elsewhere in the _source:
     *   - For a multi-field, the source path is the parent field.
     *   - One field's content could have been copied to another through copy_to.
     *
     * @param field The field for which to look up the _source path. Note that the field
     *              should be a concrete field and *not* an alias.
     * @return A set of paths in the _source that contain the field's values.
     */
    Set<String> sourcePaths(String field) {
        if (fullNameToFieldType.isEmpty()) {
            return Set.of();
        }

        // If the field is dynamically generated then return its full path
        MappedFieldType fieldType = getDynamicField(field);
        if (fieldType != null) {
            return Set.of(field);
        }

        String resolvedField = field;
        int lastDotIndex = field.lastIndexOf('.');
        if (lastDotIndex > 0) {
            String parentField = field.substring(0, lastDotIndex);
            if (fullNameToFieldType.containsKey(parentField)) {
                resolvedField = parentField;
            }
        }

        return fieldToCopiedFields.containsKey(resolvedField)
            ? fieldToCopiedFields.get(resolvedField)
            : Set.of(resolvedField);
    }
}
