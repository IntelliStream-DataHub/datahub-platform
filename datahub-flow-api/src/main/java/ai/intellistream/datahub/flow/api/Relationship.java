// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.util.Objects;

/**
 * A named outcome of a processor ({@code success}, {@code failure}, {@code alarms}…). A connection
 * links one processor's relationship to the next processor, so routing is by outcome.
 *
 * <p>Nothing disappears because someone forgot a connection: an outcome is either connected, or
 * listed in the processor's {@code terminate} list in the definition, or declares here what happens
 * when it is neither.
 *
 * @param name             stable name used in connections
 * @param description      what an item sent here means
 * @param whenUnconnected  what happens to an item sent here when nothing is connected and the
 *                         definition does not terminate it
 */
public record Relationship(String name, String description, Unconnected whenUnconnected) {

    public Relationship {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(whenUnconnected, "whenUnconnected");
    }

    /** An outcome that must be connected or terminated in every flow that uses the processor. */
    public static Relationship of(String name, String description) {
        return new Relationship(name, description, Unconnected.REQUIRED);
    }

    /** What an unconnected, unterminated relationship does with the items sent to it. */
    public enum Unconnected {
        /** Not allowed: the validator rejects a flow that leaves it unconnected. */
        REQUIRED,
        /** Items end there, as if terminated: a sink's {@code success}, an optional side output. */
        TERMINATE,
        /**
         * An item sent there fails the run: a sink's {@code failure}, a write that did not land.
         * The item's {@code failure.reason} attribute, when set, becomes part of the run's error.
         */
        FAIL_RUN
    }
}
