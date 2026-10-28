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
 * Renders the Flows section's Clients tab out of {@code flows/clients.html} in both console languages.
 * The client is created in Keycloak beforehand, so the page has to say how; a key missing from one
 * bundle would leave a {@code ??key??} where that step should be.
 */
class FlowClientsTemplateTest {

    private static String render(Locale locale) {
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

        Context context = new Context(locale);
        context.setVariable("featureDisabled", false);
        return engine.process(new TemplateSpec("flows/clients", Set.of(".settings-page"),
                TemplateMode.HTML, null), context);
    }

    @Test
    void theGuideSaysTheClientIsCreatedInKeycloakAndTheFormTakesItsCredentials() {
        assertThat(render(Locale.ENGLISH))
                .contains("Create the client in Keycloak.")
                .contains("DATAHUB_ACCESS")
                .contains("/datasets/&lt;id&gt;/write")
                .contains("Run as a client")
                .contains("name=\"clientId\"")
                .contains("type=\"password\"")
                .doesNotContain("??");
        assertThat(render(Locale.forLanguageTag("nb")))
                .contains("Opprett klienten i Keycloak.")
                .contains("Kjør som en klient")
                .doesNotContain("??");
    }
}
