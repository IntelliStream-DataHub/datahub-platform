// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

/**
 * What user-named properties mean to a processor, as in NiFi: the key is chosen by whoever writes
 * the flow, and the processor interprets it. For {@code route.on.attribute} the key is a
 * relationship name and the value the attribute value that selects it.
 *
 * @param keyDescription          what a key means
 * @param valueDescription        what a value means
 * @param type                    the type of every value
 * @param references              whether {@code ${…}} references are allowed in the values
 * @param relationshipPerProperty whether each key is also a relationship of the processor
 */
public record DynamicPropertyDescriptor(String keyDescription, String valueDescription,
                                        PropertyType type, boolean references,
                                        boolean relationshipPerProperty) {
}
