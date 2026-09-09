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
        try{
            ZonedDateTime dateTime = ZonedDateTime.parse(time, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            return dateTime.toInstant().toEpochMilli();
        } catch (DateTimeParseException we){
            return epochToMillis(Long.parseLong(time));
        }
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

    public static LocalDateTime fromEpochUTCTime(String time){
        LocalDateTime dateTime = null;
        try{
            dateTime = LocalDateTime.parse(time, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            return dateTime
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDateTime();
        } catch (DateTimeParseException we){
            long epochTime = epochToMillis(Long.parseLong(time));
            return Instant.ofEpochMilli(epochTime)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDateTime();
        }
    }

    public static ZonedDateTime fromEpochUTCTimeAsZonedDateTime(String datetime){
        ZonedDateTime dateTime = null;
        try{
            dateTime = ZonedDateTime.parse(datetime, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            // When string is "2024-02-19T22:00Z" we need to convert from Zulu time.
            // Not really necessary, it is the same timezone as UTC
            return dateTime.withZoneSameInstant(ZoneId.of("UTC"));
        } catch (DateTimeParseException we){
            long epochTime = epochToMillis(Long.parseLong(datetime));
            return Instant.ofEpochMilli(epochTime)
                    .atZone(ZoneId.of("UTC"));
        }
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
