// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a processor is, as the catalog publishes it and the validator checks a flow against it.
 *
 * <p>{@code name} and the property names are part of the contract a flow definition is written
 * against: a rename keeps the old name as an alias, and a removal is announced at least one release
 * in advance.
 *
 * @param name              stable name a flow definition refers to, e.g. {@code clip}
 * @param displayName       what the console calls it, e.g. "Clip"
 * @param category          the segment the console lists it under
 * @param version           the processor's own version, recorded by every run that uses it
 * @param description       what it does, for the console and the catalog
 * @param properties        the properties it declares
 * @param dynamicProperties what user-named properties mean to it, or null if it takes none
 * @param relationships     its outcomes
 * @param accepts           the content kinds it accepts; empty means any. Not checked in the first
 *                          version: a mismatch fails at run time
 */
public record ProcessorDescriptor(String name, String displayName, Category category, String version,
                                  String description,
                                  List<PropertyDescriptor> properties,
                                  DynamicPropertyDescriptor dynamicProperties,
                                  List<Relationship> relationships,
                                  Set<ContentKind> accepts) {

    public ProcessorDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(version, "version");
        properties = List.copyOf(properties);
        relationships = List.copyOf(relationships);
        accepts = Set.copyOf(accepts);
    }

    /** The segments of the console's processor list, in the order it shows them. */
    public enum Category {
        READ_WRITE("read-write"), TRANSFORM("transform"), DETECT("detect");

        private final String wireName;

        Category(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public static Builder builder(String name, String version) {
        return new Builder(name, version);
    }

    /** The declared property called {@code name}, or null. */
    public PropertyDescriptor property(String name) {
        for (PropertyDescriptor p : properties) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public static final class Builder {
        private final String name;
        private final String version;
        private String displayName;
        private Category category = Category.TRANSFORM;
        private String description = "";
        private final List<PropertyDescriptor> properties = new ArrayList<>();
        private DynamicPropertyDescriptor dynamicProperties;
        private final List<Relationship> relationships = new ArrayList<>();
        private Set<ContentKind> accepts = Set.of();

        private Builder(String name, String version) {
            this.name = name;
            this.version = version;
            this.displayName = name;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder category(Category category) {
            this.category = category;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder property(PropertyDescriptor property) {
            properties.add(property);
            return this;
        }

        public Builder dynamicProperties(DynamicPropertyDescriptor dynamicProperties) {
            this.dynamicProperties = dynamicProperties;
            return this;
        }

        public Builder relationship(Relationship relationship) {
            relationships.add(relationship);
            return this;
        }

        public Builder accepts(ContentKind... kinds) {
            this.accepts = Set.of(kinds);
            return this;
        }

        public ProcessorDescriptor build() {
            return new ProcessorDescriptor(name, displayName, category, version, description, properties,
                    dynamicProperties, relationships, accepts);
        }
    }
}
