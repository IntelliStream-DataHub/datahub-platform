// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats server-rendered timestamps in the user's own timezone, so pages such as the file listing
 * match the browser-side {@code window.formatLocalTime()} helper and the two never drift.
 *
 * <p>The user's real timezone is only known to the browser, so {@code application.js} writes it into
 * a {@code dh-tz} cookie (the IANA id, e.g. {@code Europe/Oslo}) from {@code Intl}. This bean reads
 * that cookie on each render and converts the stored {@link ZonedDateTime} to it. Until the cookie
 * is present (the very first request of a session) we fall back to UTC.
 *
 * <p>Java's {@code z} pattern yields the DST-aware abbreviation (CET/CEST) consistently across
 * locales, matching the {@code en-GB} abbreviation the browser helper uses, so the format string
 * here is kept identical to it: {@code yyyy.MM.dd HH:mm z}.
 *
 * <p>Exposed to Thymeleaf as {@code ${@displayTime.format(...)}}.
 */
@Component("displayTime")
public class DisplayTime {

    private static final DateTimeFormatter PATTERN =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm z", Locale.ENGLISH);
    private static final String TZ_COOKIE = "dh-tz";
    private static final ZoneId FALLBACK = ZoneId.of("UTC");

    private final HttpServletRequest request;

    public DisplayTime(HttpServletRequest request) {
        this.request = request;
    }

    /** Render a timestamp as e.g. {@code 2026.07.08 16:40 CEST} in the user's timezone. */
    public String format(ZonedDateTime value) {
        if (value == null) {
            return "";
        }
        return value.withZoneSameInstant(userZone()).format(PATTERN);
    }

    private ZoneId userZone() {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (TZ_COOKIE.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        try {
                            return ZoneId.of(value);
                        } catch (Exception ignored) {
                            // unknown/garbled zone id -> fall back to UTC
                        }
                    }
                }
            }
        }
        return FALLBACK;
    }
}
