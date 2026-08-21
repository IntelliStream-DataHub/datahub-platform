// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * Parser for relative time.
 */
public class RelativeTimeUtil {

    public static long parseRelativeTimeInSeconds(String relativeTime) {
        if (relativeTime.isEmpty()) {
            throw new IllegalArgumentException("expiry time cannot be empty");
        }

        int lastIndex =  relativeTime.length() - 1;
        char lastChar = relativeTime.charAt(lastIndex);
        final char timeUnit;

        if (!Character.isAlphabetic(lastChar)) {
            // No unit specified, assume seconds
            timeUnit = 's';
            lastIndex = relativeTime.length();
        } else {
            timeUnit = Character.toLowerCase(lastChar);
        }

        long duration = Long.parseLong(relativeTime.substring(0, lastIndex));

        return switch (timeUnit) {
            case 's' -> duration;
            case 'm' -> TimeUnit.MINUTES.toSeconds(duration);
            case 'h' -> TimeUnit.HOURS.toSeconds(duration);
            case 'd' -> TimeUnit.DAYS.toSeconds(duration);
            case 'w' -> 7 * TimeUnit.DAYS.toSeconds(duration);
            // No unit for months
            case 'y' -> 365 * TimeUnit.DAYS.toSeconds(duration);
            default ->
                    throw new IllegalArgumentException("Invalid time unit '" + lastChar + "'");
        };
    }

    /**
     * Convert nanoseconds to seconds and keep three decimal places.
     * @param ns
     * @return seconds
     */
    public static double nsToSeconds(long ns) {
        double seconds = (double) ns / 1_000_000_000;
        BigDecimal bd = new BigDecimal(seconds);
        return bd.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
