// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.util.List;

/**
 * What a processor is, as the catalog publishes it.
 *
 * <p>{@code name} is part of the contract a flow definition is written against: a rename keeps the
 * old name as an alias, and a removal is announced at least one release in advance.
 *
 * @param name          stable name a flow definition refers to, e.g. {@code timeseries.scale}
 * @param version       the processor's own version, recorded by every run that uses it
 * @param description   what it does, for the console and the catalog
 * @param relationships the named outcomes an item can leave it on, e.g. {@code success}
 */
public record ProcessorDescriptor(String name, String version, String description,
                                  List<String> relationships) {
}
