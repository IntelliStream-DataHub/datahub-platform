// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import java.util.List;
import java.util.Objects;

/**
 * A property a processor declares: what a flow definition may set under {@code name}.
 *
 * @param name             stable key used in the flow definition, e.g. {@code target}
 * @param displayName      for the console's form
 * @param description      for the console's form and the catalog
 * @param type             what kind of value it holds
 * @param required         whether a value must be set; a default satisfies it
 * @param defaultValue     the value when none is set, written as a definition would write it
 *                         (a reference such as {@code ${run.window.start}} included), or null
 * @param allowableValues  for {@link PropertyType#ENUM}: the values it may take
 * @param references       whether {@code ${…}} references are allowed in the value
 * @param sensitive        never shown or logged; the value must be a {@code ${secrets.<name>}}
 *                         reference
 */
public record PropertyDescriptor(String name, String displayName, String description,
                                 PropertyType type, boolean required, Object defaultValue,
                                 List<AllowableValue> allowableValues, boolean references,
                                 boolean sensitive) {

    public PropertyDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        allowableValues = List.copyOf(allowableValues);
    }

    public static Builder builder(String name, PropertyType type) {
        return new Builder(name, type);
    }

    /** One value an {@link PropertyType#ENUM} property may take. */
    public record AllowableValue(String value, String description) {
    }

    public static final class Builder {
        private final String name;
        private final PropertyType type;
        private String displayName;
        private String description = "";
        private boolean required;
        private Object defaultValue;
        private List<AllowableValue> allowableValues = List.of();
        private boolean references;
        private boolean sensitive;

        private Builder(String name, PropertyType type) {
            this.name = name;
            this.type = type;
            this.displayName = name;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder required() {
            this.required = true;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder allowableValues(AllowableValue... values) {
            this.allowableValues = List.of(values);
            return this;
        }

        public Builder references() {
            this.references = true;
            return this;
        }

        public Builder sensitive() {
            this.sensitive = true;
            return this;
        }

        public PropertyDescriptor build() {
            return new PropertyDescriptor(name, displayName, description, type, required,
                    defaultValue, allowableValues, references, sensitive);
        }
    }
}
