// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.util.Map;

/** A processor's configured property values. */
public interface PropertyValues {

    /** The value of a declared property: what the definition set, or else the default. */
    PropertyValue property(String name);

    /** The user-named properties, by key, in the order the definition lists them. */
    Map<String, PropertyValue> dynamicProperties();
}
