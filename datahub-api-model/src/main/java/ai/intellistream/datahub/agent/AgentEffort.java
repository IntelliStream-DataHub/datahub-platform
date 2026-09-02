// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How hard an agent should think, as a wire vocabulary.
 *
 * <p>This enum exists to make {@link AgentDefinition#defaultEffort()} a checkable part of the REST
 * contract rather than free text: a client reading the schema learns the five levels, and a
 * misspelled level is rejected on write instead of silently ignored on every later turn.
 *
 * <p>It carries the <em>names</em> only. What each level does — how many output tokens it earns,
 * how it narrows onto the three values the OpenAI-compatible wire defines — belongs to whatever
 * runs the agent, and lives in the console's {@code ChatEffort}. The two are pinned to the same
 * names by {@code ChatEffortVocabularyTest}, so this cannot drift into an api that accepts a level
 * the runner cannot honour.
 */
public enum AgentEffort {

    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** Lower-case, which is what the browser and the stored column both use. */
    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Lenient parse of a stored or submitted level.
     *
     * @return null for null or blank — meaning "not stated", so the deployment default applies
     * @throws IllegalArgumentException on a non-empty value that names no level
     */
    @JsonCreator
    public static AgentEffort parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return AgentEffort.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
