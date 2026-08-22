// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api;


import ai.intellistream.datahub.config.KeycloakVaultSecrets;
import ai.intellistream.datahub.config.PulsarVaultSecrets;
import ai.intellistream.datahub.config.VaultConfigurationLoader;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@Configuration
@ComponentScan(basePackages = {
        "ai.intellistream.datahub.config",
        "ai.intellistream.datahub.tenant",
        "ai.intellistream.datahub.jpa",
        "ai.intellistream.datahub.services",
        "ai.intellistream.datahub.api",
        "ai.intellistream.datahub.repositories",
        "ai.intellistream.datahub.clickhouse",
        "ai.intellistream.datahub.pulsar",
        "ai.intellistream.datahub.transformers",
        "ai.intellistream.datahub.validation",
        "ai.intellistream.datahub.helpers.utils",
        "org.springdoc"
})
@OpenAPIDefinition(
        info = @Info(
                title = "DataHub API",
                version = "0.3",
                license = @License(name = "AGPL", url = "https://www.gnu.org/licenses/agpl-3.0.en.html"),
                extensions = {
                        // The same wordmark the website's own navigation renders (its inline
                        // #intellistream-logo SVG), not the orange node glyph api-image.png that
                        // used to sit here. Absolute rather than root-relative on purpose: this
                        // spec is rendered both by the website at /api and by the API's own
                        // /static/redoc/redoc.html, and only an absolute URL resolves from both.
                        @Extension(name = "x-logo", properties = {
                                @ExtensionProperty(name = "url", value = "https://intellistream.ai/static/images/intellistream-logo.svg"),
                                @ExtensionProperty(name = "backgroundColor", value = "#FFFFFF"),
                                @ExtensionProperty(name = "altText", value = "IntelliStream")
                        })
                },
                description = """
                            # Authentication

                            All API endpoints are authenticated with an OAuth2 JWT access token,
                            sent in the `Authorization` header with the Bearer format:

                            ```

                            Authorization: Bearer <access token>

                            ```

                            For applications and scripts, obtain a token with the OAuth2
                            client-credentials grant against your identity provider (one service
                            account per tenant). The token must carry the `organization` claim
                            naming exactly one organization; whether that happens automatically
                            depends on how your realm produces the claim — a protocol mapper on
                            the client emits it on every token, while a realm using Keycloak
                            Organizations (such as the bundled dev realm) only emits it when the
                            request names a scope like `organization:*`. See the "Machine-to-machine
                            tokens" section of GETTING_STARTED.md and KEYCLOAK_ORG_GROUPS.md. For
                            quick manual experiments you can copy your own signed-in session's
                            token from the Console under your username ("copy token"); note it
                            expires with your session, so it is not suited to anything
                            long-running.

                            # Error responses

                            When something goes wrong the API always returns JSON in the same shape:

                            ```json
                            { "error": { ...details... } }
                            ```

                            The HTTP status code tells you the category; the `error` object tells you
                            the specifics. Each endpoint documents which statuses it can return and
                            links to the response schema. The common ones are:

                            - **400 Bad Request** — your input was rejected before anything changed.
                              Body is a `BadRequestError` with a human-readable `message` and a
                              `fields` list saying which inputs were wrong. Fix the inputs and retry.
                            - **401 Unauthorized** — your API token is missing or invalid. Check the
                              `Authorization` header.
                            - **404 Not Found** — the thing you asked for doesn't exist (wrong `id`
                              or `externalId`, or it belongs to another tenant).
                            - **409 Conflict** — two flavours. Either a `DuplicateError` ("an object
                              with this `externalId` already exists") — pick a different `externalId`
                              or use the corresponding `/update` endpoint. Or a `ConflictError`
                              ("the resource was modified or removed by another request") — re-read
                              the current state and retry.
                            - **422 Unprocessable Entity** — input was parseable but a field failed
                              validation (length, allowed characters, required-ness). Response lists
                              the offending fields.
                            - **429 Too Many Requests** — you've hit a rate limit. Back off and retry.
                            - **5xx** — something went wrong on our side. Safe to retry after a short
                              backoff; if it persists, contact support.

                            Every error body is safe to log and show to end users; it never contains
                            credentials or internal stack traces."""

        ),
        servers = {@Server(url = "https://api-{project}.intellistream.ai", description = "The url is your api-{your-project-name}.intellistream.ai")},
        // Machine-readable counterpart of the Authentication prose above, so client generators
        // and agents discover the bearer requirement from the spec instead of reading markdown.
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "OAuth2 JWT access token (client-credentials for services, or a signed-in session token)."
)
public class ApiDatahubApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ApiDatahubApplication.class);
        // Registered here, not in spring.factories, so @SpringBootTest contexts never reach Vault.
        app.addListeners(new VaultConfigurationLoader(new PulsarVaultSecrets(), new KeycloakVaultSecrets()));
        app.run(args);
    }

    @Bean
    public OpenApiCustomizer tagGroupsCustomizer() {
        return openApi -> {
            // Add custom x-tagGroups here
            openApi.addExtension("x-tagGroups", createTagGroups());

            // Sort paths by x-sort extension
            if (openApi.getPaths() != null) {
                Paths sortedPaths = new Paths();

                openApi.getPaths().entrySet().stream()
                        .sorted(Comparator.comparingInt(entry -> {
                            PathItem pathItem = entry.getValue();
                            if (pathItem == null || pathItem.readOperations().isEmpty()) {
                                return Integer.MAX_VALUE;
                            }
                            return pathItem.readOperations().stream()
                                    .mapToInt(op -> {
                                        if (op.getExtensions() == null) {
                                            return 999999;
                                        } else {
                                            return Integer.parseInt(op.getExtensions().getOrDefault("x-sort", "999999").toString());
                                        }
                                    })
                                    .min().orElse(Integer.MAX_VALUE);
                        }))
                        .forEach(entry -> sortedPaths.addPathItem(entry.getKey(), entry.getValue()));

                openApi.setPaths(sortedPaths);
            }
        };
    }

    private Object createTagGroups() {
        // Create the tag groups in the structure expected by Swagger UI
        return List.of(
                Map.of("name", "Data Organization", "tags", List.of("Labels", "Data sets", "Governance", "Relationships", "Units")),
                Map.of("name", "Asset-Centric Data", "tags", List.of("Events", "Resources", "Time-series", "Files")),
                // "Stream" is deliberately left out until the streaming API refactor lands. It is
                // also @Hidden on StreamController, which is the setting that actually matters —
                // dropping a tag from a group only removes it from Redoc's rendering, while the
                // endpoints stay readable in the raw spec. Re-list it here when un-hiding.
                Map.of("name", "Data Streaming", "tags", List.of("Subscriptions"))
        );
    }

}
