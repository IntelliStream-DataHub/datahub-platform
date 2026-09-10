// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.datetime;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateTimeHandler {

    public static final String DATETIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX";
    public static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATETIME_PATTERN);

    // An all-digit timestamp is epoch MILLISECONDS, and only within this width. Present-day millis
    // are 13 digits; the band runs from 1973-03-03 (12 digits) to the year 5138 (14). Ten digits —
    // the shape of epoch seconds — falls outside deliberately, so a seconds value is refused with a
    // message saying so rather than read as a date in 1970 the caller may not notice for weeks.
    private static final int EPOCH_MIN_DIGITS = 12;
    private static final int EPOCH_MAX_DIGITS = 14;

    // Seconds-shaped: what a caller sending the wrong unit actually types. Worth naming in the
    // error, because "not a valid timestamp" does not tell them the fix is a factor of 1000.
    private static final int SECONDS_MIN_DIGITS = 9;
    private static final int SECONDS_MAX_DIGITS = 11;

    /** The oldest epoch this accepts; anything earlier has to arrive as ISO-8601. */
    private static final String OLDEST_EPOCH = "1973-03-03";

    /**
     * Parses a timestamp in the two forms a client may send: ISO-8601, which must carry an offset
     * and keeps whatever offset or zone it has, or a UTC epoch in <em>milliseconds</em>.
     *
     * <p>The unit is never guessed. A bare number carries none, and every rule that infers one from
     * magnitude has a band where it infers wrong and says nothing — the same class of silent
     * hours-out error as reading an ISO string without its offset. So the width is checked instead
     * and anything outside it is refused, with an error naming the two forms and the range.
     *
     * <p>The single place the forms are told apart, so every entry point accepts exactly the same
     * thing. Callers wanting the value in UTC use {@link #fromEpochUTCTimeAsZonedDateTime}.
     *
     * @throws DateTimeParseException if the value is neither form, with a message the api hands
     *                                back to the caller verbatim
     */
    public static ZonedDateTime parseClientTimestamp(String value) {
        int digits = digitCount(value);
        if (digits >= EPOCH_MIN_DIGITS && digits <= EPOCH_MAX_DIGITS) {
            return ZonedDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(value)), ZoneOffset.UTC);
        }
        if (digits > 0) {
            throw new DateTimeParseException(numericAdvice(value, digits), value, 0);
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new DateTimeParseException(isoAdvice(value), value, e.getErrorIndex(), e);
        }
    }

    /**
     * The digits in an all-digit token, ignoring one leading {@code -}, or {@code -1} when the value
     * is not one. Zero-length counts as not-a-number so a blank falls to the ISO branch and fails
     * there with the ISO wording.
     */
    private static int digitCount(String value) {
        int start = !value.isEmpty() && value.charAt(0) == '-' ? 1 : 0;
        if (value.length() == start) {
            return -1;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return -1;
            }
        }
        return value.length() - start;
    }

    /** A number of the wrong width. Names the unit mistake when the value looks like seconds. */
    private static String numericAdvice(String value, int digits) {
        String advice = "'" + value + "' is not a valid timestamp. Send epoch milliseconds ("
                + EPOCH_MIN_DIGITS + "-" + EPOCH_MAX_DIGITS + " digits) or ISO-8601 with an offset "
                + "(e.g. 2026-09-09T08:56:09Z).";
        if (digits >= SECONDS_MIN_DIGITS && digits <= SECONDS_MAX_DIGITS) {
            advice += " A " + digits + "-digit value is epoch seconds — multiply it by 1000.";
        }
        // Only for a value too short to be an epoch: told to someone whose number is too long, "use
        // ISO-8601 for older timestamps" is advice about a problem they do not have.
        if (digits < EPOCH_MIN_DIGITS) {
            advice += " For timestamps before " + OLDEST_EPOCH + ", use ISO-8601.";
        }
        return advice;
    }

    /** Not a number, so it was meant as ISO-8601. The usual omission is the offset. */
    private static String isoAdvice(String value) {
        return "'" + value + "' is not a valid timestamp. Send ISO-8601 with an offset "
                + "(e.g. 2026-09-09T08:56:09Z — an offset is required, never assumed) or epoch "
                + "milliseconds (" + EPOCH_MIN_DIGITS + "-" + EPOCH_MAX_DIGITS + " digits).";
    }

    public static String getDateTimeWithZoneInfo(LocalDateTime localDateTime){
        ZonedDateTime zdt = localDateTime.atZone(ZoneId.systemDefault());
        return zdt.format(DATETIME_FORMATTER);
    }

    public static long toEpochUTCTime(LocalDate date){
        return toEpochUTCTime(date.atStartOfDay());
    }

    public static long toEpochUTCTime(LocalDateTime time){
        return time.atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    public static long toEpochUTCTime(String time){
        return parseClientTimestamp(time).toInstant().toEpochMilli();
    }

    public static long toEpochUTCTime(ZonedDateTime time){
        return time.withZoneSameInstant(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    public static LocalDateTime fromEpochUTCTime(long epochTime){
        return Instant.ofEpochMilli(epochTime)
                .atZone(ZoneId.of("UTC"))
                .toLocalDateTime();
    }

    // Delegates rather than parsing again: LocalDateTime.parse drops the offset it just read, so
    // "2024-06-17T14:34:56+02:00" used to come back as 14:34 UTC, two hours off the instant sent.
    public static LocalDateTime fromEpochUTCTime(String time){
        return fromEpochUTCTimeAsZonedDateTime(time).toLocalDateTime();
    }

    /** The same parse, moved to UTC: the instant is unchanged, the offset is no longer the caller's. */
    public static ZonedDateTime fromEpochUTCTimeAsZonedDateTime(String datetime){
        return parseClientTimestamp(datetime).withZoneSameInstant(ZoneId.of("UTC"));
    }

    public static ZonedDateTime fromEpochUTCTimeAsZonedDateTime(long epochTime){
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochTime), ZoneId.of("UTC"));
    }

    public static LocalDateTime toUTC(ZonedDateTime zonedDateTime){
        return zonedDateTime.toInstant()
                .atZone(ZoneId.of("UTC"))
                .toLocalDateTime();
    }
}
