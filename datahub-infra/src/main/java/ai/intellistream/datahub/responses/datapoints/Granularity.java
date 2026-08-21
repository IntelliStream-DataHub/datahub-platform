// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.responses.datapoints;

public class Granularity {

    public static String createPostgresGranularity(String text) throws RuntimeException{
        GranularityResult result = parseUnit(text);
        return result.digitsText + " " + toIntervalUnit(result.unit, text);
    }

    public static String createCHGranularity(String text) throws RuntimeException{
        GranularityResult result = parseUnit(text);
        return result.digitsText + " " + toIntervalUnit(result.unit, text);
    }

    /**
     * Map a parsed unit word to a SQL interval unit understood by both ClickHouse
     * ({@code toStartOfInterval(..., INTERVAL n unit)}) and Postgres ({@code INTERVAL 'n unit'}).
     * The word is matched in full (not by first letter) so {@code month} never collides with
     * {@code minute}: bare {@code m} stays minute, month needs {@code mo}/{@code month}.
     */
    private static String toIntervalUnit(String unit, String original) {
        return switch (unit) {
            case "s", "sec", "secs", "second", "seconds" -> "second";
            case "m", "min", "mins", "minute", "minutes" -> "minute";
            case "h", "hr", "hrs", "hour", "hours" -> "hour";
            case "d", "day", "days" -> "day";
            case "w", "wk", "wks", "week", "weeks" -> "week";
            case "mo", "mon", "month", "months" -> "month";
            case "y", "yr", "yrs", "year", "years" -> "year";
            default -> throw new RuntimeException("Invalid granularity format: " + original);
        };
    }

    /**
     * Split a granularity string such as {@code "30 min"}, {@code "1 week"} or {@code "120s"} into
     * its leading digits and its (whole) unit word. Spaces and other separators between the two are
     * ignored. The unit is captured in full and lower-cased so multi-letter units (week, month,
     * year) parse correctly instead of collapsing to a single ambiguous letter.
     */
    private static GranularityResult parseUnit(String text) {
        if (text == null) {
            throw new RuntimeException("Invalid granularity format: null");
        }
        StringBuilder digits = new StringBuilder();
        StringBuilder unit = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                // Digits are only valid before the unit word; a digit after letters is malformed.
                if (unit.length() > 0) {
                    throw new RuntimeException("Invalid granularity format: " + text);
                }
                digits.append(c);
            } else if (Character.isLetter(c)) {
                unit.append(c);
            }
            // ignore spaces / other separators
        }

        if (digits.length() == 0 || unit.length() == 0) {
            throw new RuntimeException("Invalid granularity format: " + text);
        }

        return new GranularityResult(unit.toString().toLowerCase(), digits.toString());
    }

    protected static class GranularityResult {

        public String unit;

        public String digitsText;

        public GranularityResult(String unit, String digitsText) {
            this.unit = unit;
            this.digitsText = digitsText;
        }
    }
}
