// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the dashboard's roadmap card out of {@code base/index.html} in both console languages.
 *
 * <p>The card is the one place the console links to the roadmap on the website, and it does so
 * per language, so the check that matters is that each locale gets its own page and every string
 * resolves. A key missing from one bundle would otherwise show up as {@code ??key_nb??} on the
 * dashboard of whoever reads it in that language.
 */
class HomeRoadmapCardTemplateTest {

    private static String renderRoadmapCard(Locale locale) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        // Without this an English render on a Norwegian build machine would pick messages_nb.
        messages.setFallbackToSystemLocale(false);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);

        return engine.process(
                new TemplateSpec("base/index", Set.of("#home-roadmap"), TemplateMode.HTML, null),
                new Context(locale));
    }

    @Test
    void theEnglishCardLinksToTheEnglishRoadmap() {
        String html = renderRoadmapCard(Locale.ENGLISH);

        assertThat(html).contains("href=\"https://intellistream.ai/roadmap\"")
                .contains("Functions &amp; flows")
                .contains("Policies")
                .contains("Agent automation")
                .doesNotContain("??");
    }

    @Test
    void theNorwegianCardLinksToTheNorwegianRoadmap() {
        String html = renderRoadmapCard(Locale.forLanguageTag("nb"));

        assertThat(html).contains("href=\"https://intellistream.ai/nb/roadmap\"")
                .contains("Funksjoner og dataflyter")
                .contains("Policyer")
                .contains("Agentautomatisering")
                .doesNotContain("??");
    }

    @Test
    void theCardCarriesWhatItsDismissalIsKeyedOn() {
        String html = renderRoadmapCard(Locale.ENGLISH);

        // The X and the version it is remembered against; the inline script and home-dashboard.js
        // both read the version off the card, so without it a dismissal could never match.
        assertThat(html).contains("data-type=\"roadmap-dismiss\"")
                .contains("aria-label=\"Hide the roadmap\"")
                .containsPattern("data-roadmap-version=\"[^\"]+\"");
    }
}
