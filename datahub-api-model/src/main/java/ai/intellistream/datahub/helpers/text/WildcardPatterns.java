// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

/**
 * The one place a caller's match pattern becomes a SQL {@code LIKE} pattern.
 *
 * <p><b>Two wildcards, and only two.</b> {@code *} and {@code %} both mean "any run of characters",
 * so {@code "sap*"}, {@code "sap%"} and {@code "*work*"} all do what they look like. Everything else
 * in the pattern is literal.
 *
 * <p><b>Underscores are literal, and that is the point.</b> SQL {@code LIKE} reads {@code _} as
 * "any single character", and this platform's identifiers are made of underscores —
 * {@code sap_work_orders}, {@code rpm_pump_1}, {@code mass_flow_rate_kghr}. Passed through raw,
 * {@code "sap_work_orders"} would also match {@code "sapXwork_orders"}, and the caller would have no
 * way to ask for the one they actually meant. The escaping below makes {@code _} mean {@code _},
 * using {@code LIKE}'s default {@code \} escape character so no {@code ESCAPE} clause is needed.
 *
 * <p>Callers that can use an index on exact values check {@link #isPattern(String)} first: an entry
 * with no wildcard is an exact match and can go through the hashed, indexed column instead of a
 * scan. That split is why this class exposes the test rather than always returning a pattern.
 */
public final class WildcardPatterns {

    private WildcardPatterns() {
    }

    /**
     * Whether this entry asks for a loose match. False means it is a literal value, which callers
     * are free to resolve through an equality or hash index instead of {@code LIKE}.
     */
    public static boolean isPattern(String value) {
        return value != null && (value.indexOf('*') >= 0 || value.indexOf('%') >= 0);
    }

    /**
     * The entry as a SQL {@code LIKE} pattern: {@code \} and {@code _} escaped so they match
     * themselves, then {@code *} translated to {@code %}.
     *
     * <p>Safe to call on a literal value too — it yields a pattern that matches exactly that
     * string, which is what makes it usable for fields with no index to fall back on.
     */
    public static String toSqlLike(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder pattern = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                // Escaped so they are matched literally. The backslash must come first, or the
                // escapes this loop adds would themselves be escaped on a later pass.
                case '\\', '_' -> pattern.append('\\').append(c);
                // The caller-facing wildcard, spelled the way a shell or a search box spells it.
                case '*' -> pattern.append('%');
                default -> pattern.append(c);
            }
        }
        return pattern.toString();
    }
}
