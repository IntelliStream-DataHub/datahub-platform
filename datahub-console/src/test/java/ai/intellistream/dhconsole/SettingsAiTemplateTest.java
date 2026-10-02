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
 * Renders the setup help on the AI assistant settings page out of {@code settings/ai.html} in both
 * console languages.
 *
 * <p>The page carries a short setup guide, three steps and a link to the full one, and explains
 * under Base URL who calls it. A key missing from one bundle would show up as {@code ??key_nb??}
 * on the page of whoever reads it in that language, which is the person trying to get the
 * assistant working.
 */
class SettingsAiTemplateTest {

    private static final String GUIDE =
            "href=\"https://intellistream.ai/data-platform-documentation/using/assistant-setup\"";

    private static String render(Locale locale, String selector) {
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
                new TemplateSpec("settings/ai", Set.of(selector), TemplateMode.HTML, null),
                new Context(locale));
    }

    @Test
    void theGuideHasThreeStepsAndTheWayToTheFullGuide() {
        String selector = "[data-type='setup-guide']";

        assertThat(render(Locale.ENGLISH, selector))
                .contains("How to set up the assistant")
                .contains("Choose where the model runs.")
                .contains("Fill in the form below and save.")
                .contains("Ask it something.")
                .contains("claude-opus-5-5")
                .contains("ending in /v1")
                .contains(GUIDE)
                .contains("Full guide, with troubleshooting")
                .doesNotContain("??");
        assertThat(render(Locale.forLanguageTag("nb"), selector))
                .contains("Slik setter du opp assistenten")
                .contains("Velg hvor modellen skal kjøre.")
                .contains("Spør KI")
                .contains(GUIDE)
                .doesNotContain("??");
    }

    @Test
    void theGuideIsTheOnlyLinkOut() {
        // One way to the full guide, inside the box, rather than a link in every paragraph.
        assertThat(render(Locale.ENGLISH, ".settings-header")).doesNotContain("href=");
        assertThat(render(Locale.ENGLISH, "[data-type='settings-unconfigured']"))
                .contains("Your assistant is not running yet.")
                .doesNotContain("href=");
    }

    @Test
    void theGuideStartsFoldedUntilTheScriptKnowsWhetherAModelIsConfigured() {
        // settings-ai.js opens it on load for a tenant with nothing configured. Rendered open, it
        // would flash open and fold again for every tenant that has a model.
        assertThat(render(Locale.ENGLISH, "[data-type='setup-guide']"))
                .contains("<details class=\"settings-guide\" data-type=\"setup-guide\">");
    }

    @Test
    void theBaseUrlHelpSaysDataHubCallsItNotTheBrowser() {
        // The model section: the fields, their placeholders and the help beneath them.
        assertThat(render(Locale.ENGLISH, ".settings-group"))
                .contains("up to and including /v1")
                .contains("not your browser")
                .contains("placeholder=\"http://your-model-server:11434/v1\"")
                .contains("placeholder=\"e.g. claude-opus-5-5 or qwen3.8:latest\"")
                .doesNotContain("??");
        assertThat(render(Locale.forLanguageTag("nb"), ".settings-group"))
                .contains("ikke nettleseren din")
                .doesNotContain("??");
    }
}
