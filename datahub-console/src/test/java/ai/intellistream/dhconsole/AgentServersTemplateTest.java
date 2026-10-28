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
 * Renders the Agents section's Tool servers tab out of {@code agents/servers.html} in both console
 * languages: the list and the form carry the hooks the script fills, and no key is missing from
 * either bundle, which would show as {@code ??key??} to the person registering a server.
 */
class AgentServersTemplateTest {

    private static String render(Locale locale, String template, Set<String> selectors, String pageSelected) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
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
        engine.setLinkBuilder(FlowsTemplateTest.ROOT_LINKS);

        Context context = new Context(locale);
        context.setVariable("featureDisabled", false);
        context.setVariable("pageSelected", pageSelected);
        return engine.process(new TemplateSpec(template, selectors, TemplateMode.HTML, null), context);
    }

    private static String page(Locale locale, String selector) {
        return render(locale, "agents/servers", Set.of(selector), "servers");
    }

    @Test
    void theListAndTheFormCarryTheHooksTheScriptFills() {
        assertThat(page(Locale.ENGLISH, "[data-type='tool-servers']"))
                .contains("data-type=\"server-list-group\"")
                .contains("data-type=\"server-list\"")
                .contains("data-type=\"server-list-empty\"")
                .contains("data-type=\"mcp-server-form\"")
                .contains("data-type=\"mcp-server-form-title\"")
                .contains("data-type=\"headervalue-help\"")
                .contains("data-type=\"cancel\"")
                .contains("name=\"headerValue\" type=\"password\"")
                .contains("data-error-for=\"url\"")
                .contains("Registered tool servers")
                .contains("&lt;name&gt;__&lt;tool&gt;")
                .doesNotContain("??");
    }

    @Test
    void bothLanguagesSayWhoCallsTheServerAndThatTheCredentialIsNeverShown() {
        assertThat(page(Locale.ENGLISH, ".settings-page"))
                .contains("not your browser")
                .contains("never shown again")
                .doesNotContain("??");
        assertThat(page(Locale.forLanguageTag("nb"), ".settings-page"))
                .contains("ikke nettleseren din")
                .contains("vises aldri igjen")
                .doesNotContain("??");
    }

    @Test
    void theTabIsInTheSectionsNav() {
        assertThat(render(Locale.ENGLISH, "agents/nav", null, "servers"))
                .contains("href=\"/agents?tab=servers\"")
                .contains(">Tool servers<")
                .doesNotContain("??");
    }
}
