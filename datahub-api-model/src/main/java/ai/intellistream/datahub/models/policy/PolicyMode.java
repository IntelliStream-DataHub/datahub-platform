// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import java.util.Locale;

/** What a policy does when a value does not conform. */
public enum PolicyMode {

    /**
     * The write fails and nothing in the batch is created.
     *
     * <p>The default, and the better of the two failure modes: a clear error at creation time,
     * versus silent ambiguity discovered months later when a search comes back short.
     */
    REJECT,

    /**
     * The write succeeds and a finding is recorded for a steward.
     *
     * <p>Meaningful precisely because findings are persisted — it means "allowed and in the
     * steward's queue", not "allowed and forgotten". This is the escape hatch for a facility that
     * genuinely maintains two similar-looking ids as distinct tags.
     */
    WARN;

    public static PolicyMode parse(String raw, PolicyMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public PolicyDecision toDecision() {
        return this == REJECT ? PolicyDecision.NOT_OK : PolicyDecision.WARNING;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
