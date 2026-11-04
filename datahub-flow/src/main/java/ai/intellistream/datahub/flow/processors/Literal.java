// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.PropertyValue;

import java.time.Duration;

/**
 * A property's value as written, for {@code validate}: when a flow is saved a {@code ${…}} reference
 * is set but has no value yet, so a check on it waits for the run. These answer null for a value
 * that is unset or a reference.
 */
final class Literal {

    private Literal() {
    }

    static Double number(PropertyValue value) {
        try {
            return value.asDouble();
        } catch (ProcessException reference) {
            return null;
        }
    }

    static Duration duration(PropertyValue value) {
        try {
            return value.asDuration();
        } catch (ProcessException reference) {
            return null;
        }
    }
}
