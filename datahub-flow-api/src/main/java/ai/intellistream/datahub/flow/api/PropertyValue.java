// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One property's value. The {@code as…} methods convert it; a value that does not fit the type —
 * which only a substituted reference can produce, since literals are checked when the flow is
 * saved — throws a {@link ProcessException}. So does reading a value that still refers to the item:
 * {@link #evaluate(Item)} it first.
 */
public interface PropertyValue {

    /** Whether the property has a value, set or by default. */
    boolean isSet();

    /** Whether the value refers to the item, so has to be {@link #evaluate(Item) evaluated}. */
    boolean refersToItem();

    /** This value with the item's references resolved against {@code item}. */
    PropertyValue evaluate(Item item);

    /** Null when not set. */
    String asString();

    Double asDouble();

    Boolean asBoolean();

    Duration asDuration();

    Instant asInstant();

    List<String> asList();
}
