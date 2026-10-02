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
 * <p>The page links to the step-by-step setup guide twice, in the intro and in the banner shown
 * while the assistant is not running yet, and explains under Base URL who calls it. A key missing
 * from one bundle would show up as {@code ??key_nb??} on the page of whoever reads it in that
 * language, which is the person trying to get the assistant working.
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
    void theIntroLinksToTheSetupGuide() {
        assertThat(render(Locale.ENGLISH, ".settings-header"))
                .contains(GUIDE)
                .contains("Step-by-step setup guide")
                .doesNotContain("??");
        assertThat(render(Locale.forLanguageTag("nb"), ".settings-header"))
                .contains(GUIDE)
                .contains("Veiledning steg for steg")
                .doesNotContain("??");
    }

    @Test
    void theNotRunningYetBannerLinksToTheSetupGuide() {
        String selector = "[data-type='settings-unconfigured']";

        assertThat(render(Locale.ENGLISH, selector))
                .contains("Your assistant is not running yet.")
                .contains(GUIDE)
                .doesNotContain("??");
        assertThat(render(Locale.forLanguageTag("nb"), selector))
                .contains(GUIDE)
                .doesNotContain("??");
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
