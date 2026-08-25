// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import ai.intellistream.dhconsole.config.AboutInfo;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
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
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the About dialog out of {@code layout/main.html} for real, through Spring's Thymeleaf.
 *
 * <p>This exists because a template expression that throws does not fail loudly. Thymeleaf streams
 * its output, so the header is already on the wire by the time the dialog is reached: the browser
 * gets a page truncated at the point of the error, missing every {@code <script>} that follows it.
 * The console still looks right and every JavaScript-driven control is silently dead. The bug that
 * prompted this test was exactly that, a {@code @bean} reference inside {@code th:href}, which
 * Thymeleaf evaluates in SpEL restricted mode where bean access is forbidden.
 *
 * <p>So the assertion that matters most is the first one: that rendering completes at all.
 *
 * <p>The beans are registered by hand rather than through a nested {@code @Configuration}, which
 * would be a Spring stereotype sitting outside the application's declared base packages and would
 * fail {@link ComponentScanCoverageTest}.
 */
class AboutDialogTemplateTest {

    private static AnnotationConfigApplicationContext contextWithAboutBeans() {
        var context = new AnnotationConfigApplicationContext();

        Properties build = new Properties();
        build.setProperty("version", "1.2.3");
        build.setProperty("time", "2026-08-22T11:26:12Z");
        context.registerBean(BuildProperties.class, () -> new BuildProperties(build));

        // Forced to singleton: UserSession is declared @Scope("session"), and registerBean honours
        // the class annotation, which would need a live request to resolve.
        context.registerBean("userSession", UserSession.class, () -> {
            UserSession session = new UserSession();
            session.setName("Ada Lovelace");
            session.setOrganizationName("Analytical Engines");
            return session;
        }, definition -> definition.setScope("singleton"));

        // Bean name, not class name: the template reaches it as ${@aboutInfo...}.
        context.registerBean("aboutInfo", AboutInfo.class,
                () -> new AboutInfo(context.getBeanProvider(BuildProperties.class),
                        "https://api.example.test", "https://analysis.example.test"));

        context.refresh();
        return context;
    }

    private static String renderAboutDialog(AnnotationConfigApplicationContext context) {
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

        Context templateContext = new Context(Locale.ENGLISH);
        // What ThymeleafView normally puts in the model; without it SpEL has no bean resolver.
        templateContext.setVariable(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(context, null));

        return engine.process(
                new TemplateSpec("layout/main", Set.of("#about-dialog"), TemplateMode.HTML, null),
                templateContext);
    }

    @Test
    void theAboutDialogRendersWithoutAbortingThePage() {
        try (var context = contextWithAboutBeans()) {
            String html = renderAboutDialog(context);

            // The licence notice is the console's AGPL section 13 source offer, so the link to the
            // repository has to survive: without it the running console makes no offer at all.
            assertThat(html).contains("https://github.com/IntelliStream-DataHub/datahub-platform");
            assertThat(html).contains("GNU Affero General Public License");

            // Built from the configured api base, and the reason for the restricted-mode bug.
            assertThat(html).contains("href=\"https://api.example.test/swagger-ui.html\"");

            // Version and build stamp, the two facts an About box exists to report.
            assertThat(html).contains("v1.2.3").contains("2026-08-22 11:26 UTC");

            // The deployment facts a support conversation is read off.
            assertThat(html).contains("Ada Lovelace")
                    .contains("Analytical Engines")
                    .contains("https://analysis.example.test");

            // Every message key resolved; Thymeleaf renders ??key_locale?? for a missing one.
            assertThat(html).doesNotContain("??");
        }
    }
}
