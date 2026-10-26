// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.catalog;

import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.api.DynamicPropertyDescriptor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor.AllowableValue;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.Relationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A processor's catalog entry: its name, version and relationships, and its properties as a JSON
 * Schema (2020-12) for the {@code properties} object of a processor in a flow definition — what the
 * console generates its form from and what an agent reads.
 *
 * <p>Built by hand from the descriptor: the descriptor is the source of truth, and the mapping is
 * small enough that a schema generator would add a dependency and nothing else. What JSON Schema
 * cannot say is carried in {@code x-} keywords: {@code x-references} (the value may contain
 * {@code ${…}} references), {@code x-sensitive}, {@code x-lookup} (what to search for the value),
 * {@code x-advanced}, {@code x-required} (required, though a default satisfies it), {@code x-tabs}
 * (a boolean's false and true, offered as two tabs) and {@code x-onlyWith} (the boolean it applies
 * only when true).
 */
final class ProcessorSchema {

    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    /** A value that is exactly one {@code ${…}} reference, accepted for any type that allows them. */
    static final String REFERENCE = "^\\$\\{[^}]+\\}$";
    static final String SECRET_REFERENCE = "^\\$\\{secrets\\.[^}]+\\}$";

    private ProcessorSchema() {
    }

    static Map<String, Object> of(ProcessorDescriptor descriptor) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", descriptor.name());
        entry.put("displayName", descriptor.displayName());
        entry.put("category", descriptor.category().wireName());
        entry.put("version", descriptor.version());
        entry.put("description", descriptor.description());
        entry.put("accepts", descriptor.accepts().stream().map(ContentKind::wireName).sorted().toList());

        List<Map<String, Object>> relationships = new ArrayList<>();
        for (Relationship r : descriptor.relationships()) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("name", r.name());
            rel.put("description", r.description());
            rel.put("whenUnconnected", r.whenUnconnected().name().toLowerCase().replace('_', '-'));
            relationships.add(rel);
        }
        entry.put("relationships", relationships);
        DynamicPropertyDescriptor dynamic = descriptor.dynamicProperties();

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (PropertyDescriptor p : descriptor.properties()) {
            properties.put(p.name(), property(p));
            if (p.required() && p.defaultValue() == null) {
                required.add(p.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", DIALECT);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        if (dynamic == null) {
            schema.put("additionalProperties", false);
        } else {
            Map<String, Object> additional = withReferences(type(dynamic.type()), dynamic.type(),
                    dynamic.references());
            additional.put("description", dynamic.valueDescription());
            additional.put("x-keyDescription", dynamic.keyDescription());
            schema.put("additionalProperties", additional);
        }
        entry.put("properties", schema);
        return entry;
    }

    private static Map<String, Object> property(PropertyDescriptor p) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("title", p.displayName());
        if (p.description() != null && !p.description().isEmpty()) {
            schema.put("description", p.description());
        }
        schema.putAll(withReferences(type(p), p.type(), p.references()));
        if (p.defaultValue() != null) {
            schema.put("default", p.defaultValue());
        }
        if (p.sensitive()) {
            schema.put("x-sensitive", true);
        }
        if (p.lookup() != null) {
            schema.put("x-lookup", p.lookup());
        }
        if (p.advanced()) {
            schema.put("x-advanced", true);
        }
        if (p.required() && p.defaultValue() != null) {
            schema.put("x-required", true);
        }
        if (!p.tabs().isEmpty()) {
            schema.put("x-tabs", p.tabs());
        }
        if (p.onlyWith() != null) {
            schema.put("x-onlyWith", p.onlyWith());
        }
        return schema;
    }

    /** The type's schema, or for one that takes references, that or a reference. */
    private static Map<String, Object> withReferences(Map<String, Object> type, PropertyType kind,
                                                      boolean references) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (!references) {
            schema.putAll(type);
            return schema;
        }
        // A string may hold references anywhere in it; any other type may be one reference.
        if (kind == PropertyType.STRING) {
            schema.putAll(type);
        } else {
            schema.put("anyOf", List.of(type, Map.of("type", "string", "pattern", REFERENCE)));
        }
        schema.put("x-references", true);
        return schema;
    }

    private static Map<String, Object> type(PropertyDescriptor p) {
        Map<String, Object> schema = type(p.type());
        if (p.type() == PropertyType.ENUM) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (AllowableValue v : p.allowableValues()) {
                values.add(Map.of("const", v.value(), "title", v.displayName(), "description", v.description()));
            }
            schema.put("oneOf", values);
        }
        return schema;
    }

    private static Map<String, Object> type(PropertyType type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        switch (type) {
            case STRING, ENUM -> schema.put("type", "string");
            case NUMBER -> schema.put("type", "number");
            case BOOLEAN -> schema.put("type", "boolean");
            case DURATION -> {
                schema.put("type", "string");
                schema.put("format", "duration");
            }
            case INSTANT -> {
                schema.put("type", "string");
                schema.put("format", "date-time");
            }
            case LIST -> {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "string"));
            }
            case SECRET -> {
                schema.put("type", "string");
                schema.put("pattern", SECRET_REFERENCE);
            }
        }
        return schema;
    }
}
