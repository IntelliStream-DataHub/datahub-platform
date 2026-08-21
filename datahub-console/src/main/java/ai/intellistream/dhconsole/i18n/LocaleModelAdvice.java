// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.i18n;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;
import java.util.Set;

@ControllerAdvice
public class LocaleModelAdvice {

    /**
     * Global switch for whether the guided-tutorial scripts are loaded on a page.
     * Defaults to on; set {@code tutorial.enabled=false} to disable everywhere.
     * Bound to model attribute {@code tutorialActive} for the layout fragment.
     */
    @Value("${tutorial.enabled:true}")
    private boolean tutorialEnabled;

    @ModelAttribute("tutorialActive")
    public boolean tutorialActive() {
        return tutorialEnabled;
    }

    private static final Set<String> TWENTY_FOUR_HOUR_LANGUAGES = Set.of(
            "nb", "nn", "no", "sv", "da", "fi", "is",
            "de", "fr", "es", "it", "nl", "pl", "pt", "ru"
    );

    /**
     * Locale tag to apply to {@code <input type="time">} elements so the browser
     * picker renders in 24-hour format. Norwegian/Swedish/etc. resolve to their
     * own language tag (already 24h). Anything that resolves to {@code en} (or
     * a region of English that uses 12h, like {@code en-US}) is mapped to
     * {@code en-GB}, which Firefox/Chrome both render as 24h. Bound to model
     * attribute {@code timeInputLang} for use in Thymeleaf templates.
     */
    @ModelAttribute("timeInputLang")
    public String timeInputLang() {
        Locale locale = LocaleContextHolder.getLocale();
        String language = locale != null ? locale.getLanguage() : "";
        if (TWENTY_FOUR_HOUR_LANGUAGES.contains(language)) {
            return locale.toLanguageTag();
        }
        return "en-GB";
    }

    /**
     * Raw resolved Spring locale as a BCP-47 tag (e.g. {@code nb}, {@code en-US}).
     * Used by the custom datepicker.js to localize month/weekday names. Unlike
     * {@link #timeInputLang()} this returns the locale verbatim — the picker
     * always renders 24h regardless, so we don't need the en-GB workaround here.
     */
    @ModelAttribute("pageLocale")
    public String pageLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null ? locale.toLanguageTag() : "en";
    }
}
