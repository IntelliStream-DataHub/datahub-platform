// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

/**
 * What user-named properties mean to a processor, as in NiFi: the key is chosen by whoever writes
 * the flow, and the processor interprets it. For {@code write-events} the key is a metadata key
 * and the value what it is set to.
 *
 * @param keyDescription   what a key means
 * @param valueDescription what a value means
 * @param type             the type of every value
 * @param references       whether {@code ${…}} references are allowed in the values
 */
public record DynamicPropertyDescriptor(String keyDescription, String valueDescription,
                                        PropertyType type, boolean references) {
}
