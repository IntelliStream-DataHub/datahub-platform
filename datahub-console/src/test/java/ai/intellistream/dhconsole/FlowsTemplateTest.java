// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders each page of the Flows section in both console languages, the part below the layout
 * that the page itself owns. Every value on these pages comes from the browser, so what can go
 * wrong on the server is a message key missing from one bundle, which Thymeleaf renders as
 * {@code ??key_nb??}, or the markup the page scripts look up by {@code data-type} going missing.
 */
class FlowsTemplateTest {

    /**
     * Resolves {@code @{/flows}} as the console does at its root. Thymeleaf otherwise refuses a
     * context-relative link outside a web request, and these pages are full of them.
     */
    static final StandardLinkBuilder ROOT_LINKS = new StandardLinkBuilder() {
        @Override
        protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
            return "";
        }
    };

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
        engine.setLinkBuilder(ROOT_LINKS);

        Context context = new Context(locale);
        context.setVariable("featureDisabled", false);
        context.setVariable("flowExternalId", "temp_hourly");
        context.setVariable("runId", "0190a5d2-0000-7000-8000-000000000001");
        return engine.process(new TemplateSpec(template, Set.of(selector), TemplateMode.HTML, null), context);
    }

    static Stream<Arguments> pages() {
        return Stream.of(
                Arguments.of("flows/index", "[data-type='flows-list-page']",
                        new String[]{"flows-table", "flows-create", "flows-load-more"}),
                Arguments.of("flows/flow", "[data-type='flow-page']",
                        new String[]{"flow-canvas", "flow-json", "flow-save", "flow-preview",
                                "flow-deploy", "flow-run-as", "flow-toggle", "flow-versions", "flow-runs",
                                "flow-new", "flow-new-id", "flow-add-panel", "flow-add-search", "flow-add-list",
                                "flow-problems"}),
                Arguments.of("flows/run", "[data-type='run-page']",
                        new String[]{"run-status", "run-facts", "run-records", "run-cancel", "run-rerun"}),
                Arguments.of("flows/attention", "[data-type='attention-page']",
                        new String[]{"attention-table", "attention-more"}),
                Arguments.of("flows/lookup", "[data-type='lookup-page']",
                        new String[]{"lookup-form", "lookup-latest", "lookup-history"}),
                Arguments.of("flows/nav", ".sub-nav", new String[]{}));
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
        if (template.equals("flows/nav")) {
            assertThat(render(template, selector, Locale.ENGLISH)).contains("Failed runs");
            assertThat(render(template, selector, Locale.forLanguageTag("nb"))).contains("Mislykkede kjøringer");
            return;
        }
        assertThat(render(template, selector, Locale.ENGLISH))
                .isNotEqualTo(render(template, selector, Locale.forLanguageTag("nb")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void theFlowAndRunIdsReachTheScripts(String template, String selector, String[] dataTypes) {
        String html = render(template, selector, Locale.ENGLISH);
        if (template.equals("flows/flow")) {
            assertThat(html).contains("data-flow-id=\"temp_hourly\"");
        }
        if (template.equals("flows/run")) {
            assertThat(html).contains("data-flow-id=\"temp_hourly\"")
                    .contains("data-run-id=\"0190a5d2-0000-7000-8000-000000000001\"")
                    .contains("href=\"/flows/temp_hourly\"");
        }
    }
}
