// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.datetime;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DateTimeHandler {

    public static final String DATETIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX";
    public static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATETIME_PATTERN);

    // Below this an epoch reads as seconds, at or above it as milliseconds. The ceiling is 13
    // digits, where present-day milliseconds sit; present-day seconds are 10.
    private static final long EPOCH_SECONDS_CEILING = 1_000_000_000_000L;

    /**
     * Normalises a client-supplied epoch to milliseconds. The digits alone do not say which unit
     * the caller meant, so magnitude decides: below the ceiling is seconds and scales up, at or
     * above it is already milliseconds. The cost of the split is the range it gives up, a
     * millisecond value from before 2001-09-09, which is read as seconds instead.
     *
     * <p>Only for values off the wire. Epochs already held as milliseconds internally (the graph,
     * ClickHouse, {@code EventModel}'s own field) must not pass through here.
     */
    public static long epochToMillis(long epoch) {
        boolean isSeconds = epoch > -EPOCH_SECONDS_CEILING && epoch < EPOCH_SECONDS_CEILING;
        return isSeconds ? epoch * 1000L : epoch;
    }

    // An all-digit value needs at least this many digits to be an epoch, so a stray "2024" fails as
    // a malformed ISO-8601 string instead of quietly becoming a timestamp in 1970.
    private static final int EPOCH_MIN_DIGITS = 9;
    private static final int EPOCH_MAX_DIGITS = 14;

    /**
     * Parses a timestamp in the forms a client may send: ISO-8601, which keeps whatever offset or
     * zone it carries, or a UTC epoch read as seconds or milliseconds by {@link #epochToMillis}.
     *
     * <p>The single place the two forms are told apart, so every entry point accepts exactly the
     * same thing. Callers wanting the value in UTC use {@link #fromEpochUTCTimeAsZonedDateTime}.
     *
     * @throws DateTimeParseException if the value is neither form
     */
    public static ZonedDateTime parseClientTimestamp(String value) {
        if (isEpoch(value)) {
            long millis = epochToMillis(Long.parseLong(value));
            return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
        }
        return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    /** True when the text is an epoch number rather than an ISO-8601 string. */
    private static boolean isEpoch(String value) {
        int start = value.startsWith("-") ? 1 : 0;
        int digits = value.length() - start;
        if (digits < EPOCH_MIN_DIGITS || digits > EPOCH_MAX_DIGITS) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
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
