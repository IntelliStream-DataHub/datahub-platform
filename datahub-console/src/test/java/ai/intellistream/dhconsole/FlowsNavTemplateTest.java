// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import ai.intellistream.dhconsole.config.FlowAccess;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Flows entry in the header is there for a tenant with the flow flag and absent otherwise, and
 * carries the element the Failed-runs count is written into.
 *
 * <p>Rendered through Spring's Thymeleaf with a real bean resolver, as {@link AboutDialogTemplateTest}
 * does: a {@code @bean} expression in the header that throws would truncate every page at the header.
 */
class FlowsNavTemplateTest {

    private static String renderNav(boolean flows) {
        FlowAccess flowAccess = mock(FlowAccess.class);
        when(flowAccess.available()).thenReturn(flows);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("flowAccess", FlowAccess.class, () -> flowAccess);
            context.refresh();

            ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
            messages.setBasename("i18n/messages");
            messages.setDefaultEncoding("UTF-8");

            SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
            resolver.setApplicationContext(context);
            resolver.setPrefix("classpath:/templates/");
            resolver.setSuffix(".html");
            resolver.setTemplateMode(TemplateMode.HTML);
            resolver.setCacheable(false);

            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(resolver);
            engine.setTemplateEngineMessageSource((MessageSource) messages);
            engine.setLinkBuilder(FlowsTemplateTest.ROOT_LINKS);

            Context templateContext = new Context(Locale.ENGLISH);
            templateContext.setVariable(
                    ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new ThymeleafEvaluationContext(context, null));
            templateContext.setVariable("pageSelected", "flows");
            return engine.process(new TemplateSpec("layout/main", Set.of("header/nav"), TemplateMode.HTML, null),
                    templateContext);
        }
    }

    @Test
    void aTenantWithFlowsSeesTheEntryAndItsCount() {
        String nav = renderNav(true);

        assertThat(nav).contains("href=\"/flows\"")
                .contains(">Flows<")
                .contains("data-type=\"flows-attention-count\"")
                .doesNotContain("??");
    }

    @Test
    void aTenantWithoutFlowsDoesNot() {
        assertThat(renderNav(false)).doesNotContain("/flows").contains("/events/index");
    }
}
