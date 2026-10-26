// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders each page of the Agents section in both console languages, as {@link FlowsTemplateTest}
 * does for Flows: a message key missing from one bundle, or the markup the page scripts look up
 * by {@code data-type} going missing, is what can go wrong on the server.
 */
class AgentsTemplateTest {

    private static String render(String template, String selector, Locale locale) {
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
        context.setVariable("flowExternalId", "pump_watch");
        context.setVariable("runId", "0190a5d2-0000-7000-8000-000000000001");
        return engine.process(new TemplateSpec(template, Set.of(selector), TemplateMode.HTML, null), context);
    }

    static Stream<Arguments> pages() {
        return Stream.of(
                Arguments.of("agents/index", "[data-type='flows-list-page']",
                        new String[]{"flows-table", "flows-create", "flows-load-more"}),
                Arguments.of("agents/agent", "[data-type='agent-page']",
                        new String[]{"agent-form", "agent-save", "agent-preview", "agent-run", "agent-deploy",
                                "agent-run-as", "agent-toggle", "agent-open-flow", "agent-delete", "agent-readonly",
                                "agent-problems", "agent-new-id", "agent-tools", "agent-timezones", "flow-runs",
                                "flow-runs-previews", "flow-runs-more", "flow-versions", "agents-attention-page",
                                "attention-table", "attention-dismiss-all"}),
                Arguments.of("agents/run", "[data-type='run-page']",
                        new String[]{"run-status", "run-facts", "run-records", "run-records-following", "run-cancel",
                                "run-rerun", "agent-transcript", "transcript-rows", "transcript-empty",
                                "transcript-outcome", "transcript-usage"}),
                Arguments.of("agents/attention", "[data-type='agents-attention-page']",
                        new String[]{"attention-table", "attention-more", "attention-dismiss-all"}),
                Arguments.of("agents/nav", ".sub-nav", new String[]{}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void everyPageRendersInBothLanguagesWithTheMarkupItsScriptNeeds(String template, String selector,
                                                                     String[] dataTypes) {
        for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.forLanguageTag("nb")}) {
            String html = render(template, selector, locale);
            assertThat(html).as("%s in %s", template, locale).isNotBlank().doesNotContain("??");
            for (String type : dataTypes) {
                assertThat(html).as("%s keeps data-type %s", template, type)
                        .contains("data-type=\"" + type + "\"");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void theSameKeysSayDifferentThingsInNorwegian(String template, String selector, String[] dataTypes) {
        assertThat(render(template, selector, Locale.ENGLISH))
                .isNotEqualTo(render(template, selector, Locale.forLanguageTag("nb")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void thePagesTellTheSharedScriptsWhichSectionTheyAre(String template, String selector, String[] dataTypes) {
        String html = render(template, selector, Locale.ENGLISH);
        switch (template) {
            case "agents/index" -> assertThat(html).contains("data-kind=\"agent\"").contains("data-section=\"agents\"")
                    .contains("href=\"/agents?new=\"");
            case "agents/agent" -> assertThat(html).contains("data-flow-id=\"pump_watch\"")
                    .contains("data-section=\"agents\"").contains("data-flow=\"pump_watch\"");
            case "agents/run" -> assertThat(html).contains("data-section=\"agents\"")
                    .contains("data-run-id=\"0190a5d2-0000-7000-8000-000000000001\"")
                    .contains("href=\"/agents/pump_watch\"");
            default -> { }
        }
    }
}
