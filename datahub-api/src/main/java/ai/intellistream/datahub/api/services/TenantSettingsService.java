// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.FieldErrors;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.helpers.utils.OutboundUrls;
import ai.intellistream.datahub.models.tenant.TenantFlowClientForm;
import ai.intellistream.datahub.models.tenant.TenantFlowMcpServerForm;
import ai.intellistream.datahub.models.tenant.TenantFlowSecretForm;
import ai.intellistream.datahub.models.tenant.TenantFlowSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Reads and changes the calling tenant's own settings.
 *
 * <p>Validation is the point of this class. The settings land in Vault, which will accept any
 * string, and are then read by a different process that has no way to complain — a tenant that
 * saves a model name with a typo does not find out here, it finds out when its assistant stops
 * answering. So everything that can be checked is checked before the write.
 *
 * <p>Access is <strong>not</strong> checked here; the controller does that. Kept there because
 * this class holds the platform's Vault credential and a service that both authorises and acts is
 * one refactor away from being called from somewhere that skipped the check.
 */
@Slf4j
@Service
public class TenantSettingsService {

    /** An RFC 9110 token: what a header name may consist of. */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Headers the MCP client sets itself, or the JDK refuses to let a caller set. */
    private static final Set<String> CLIENTS_OWN_HEADERS = Set.of("host", "content-length", "transfer-encoding",
            "connection", "content-type", "accept", "mcp-session-id", "mcp-protocol-version");

    private final TenantConfigService tenantConfigService;
    private final TenantConfigWriter configWriter;
    private final JsonMapper jsonMapper;
    private final HttpClient http;
    private final boolean allowPrivateNetworks;

    public TenantSettingsService(TenantConfigService tenantConfigService,
                                 TenantConfigWriter configWriter, JsonMapper jsonMapper,
                                 @Value("${datahub.outbound.allow-private-networks:false}") boolean allowPrivateNetworks) {
        this.tenantConfigService = tenantConfigService;
        this.configWriter = configWriter;
        this.jsonMapper = jsonMapper;
        this.allowPrivateNetworks = allowPrivateNetworks;
        // The URL is the tenant's, so no redirects: one hop to where it says, never onwards.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Never includes the credential — see {@link TenantLlmSettings#apiKeySet()}. */
    public TenantLlmSettings readLlm() {
        TenantLlm llm = currentTenant().getLlm();
        if (llm == null) {
            return TenantLlmSettings.none();
        }
        return new TenantLlmSettings(
                llm.getProvider() == null ? null : llm.getProvider().wireName(),
                llm.getModel(),
                llm.getBaseUrl(),
                llm.getReasoningEffort(),
                llm.getEffort(),
                llm.getTurnTimeout(),
                llm.getMaxOutputTokensValue(),
                llm.getMaxIterationsValue(),
                llm.getInstructions(),
                llm.getApiKey() != null && !llm.getApiKey().isBlank(),
                llm.isUsable());
    }

    /**
     * Validates and writes, then reloads the tenant registry so this instance answers with what it
     * just stored rather than what it had cached.
     *
     * <p>Other processes — the console, other api instances — keep their own caches and pick the
     * change up on their own refresh, within five minutes. Nothing here can shorten that, so the UI
     * says so rather than implying the change is live everywhere the moment it is saved.
     */
    public TenantLlmSettings updateLlm(TenantLlmSettingsForm form) {
        Tenant tenant = currentTenant();
        TenantLlm existing = tenant.getLlm();
        LlmSection section = validated(form, existing);

        configWriter.writeLlmSection(tenant.getOrganizationName(), section.keys(),
                section.keepStoredApiKey());
        tenantConfigService.refreshCache();
        return readLlm();
    }

    /**
     * The flow credentials' names, read from Vault itself rather than the tenant cache, which does
     * not hold them. Never a value.
     */
    public TenantFlowSettings readFlow() {
        return flowSettings(configWriter.current(currentTenant().getOrganizationName()));
    }

    /** Stores or replaces the secret {@code name}; the value is never returned. */
    public void putFlowSecret(String name, TenantFlowSecretForm form) {
        var errors = new FieldErrors();
        checkFlowName(name, errors);
        String value = form == null ? null : form.value();
        if (value == null || value.isBlank()) {
            errors.addFieldError("value", "A value is required.");
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException("The secret is invalid.", errors);
        }
        updateFlow(TenantFlowSection.putSecret(name, value));
    }

    /** Removes the secret {@code name}. Removing one that is not there is not an error. */
    public void removeFlowSecret(String name) {
        var errors = new FieldErrors();
        checkFlowName(name, errors);
        if (!errors.isEmpty()) {
            throw new BadRequestException("The secret name is invalid.", errors);
        }
        updateFlow(TenantFlowSection.removeSecret(name));
    }

    /**
     * Registers or replaces the client {@code name}. Its credentials are not tried here: the flow
     * service takes a token with them when a deployment names the client, and fails clearly there.
     */
    public void putFlowClient(String name, TenantFlowClientForm form) {
        var errors = new FieldErrors();
        checkFlowName(name, errors);
        String clientId = form == null ? null : trimmed(form.clientId());
        // Not trimmed: a secret is whatever Keycloak issued, and only blank means absent.
        String clientSecret = form == null ? null : form.clientSecret();
        if (clientId == null) {
            errors.addFieldError("clientId", "A client id is required.");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            errors.addFieldError("clientSecret",
                    "A client secret is required, also when replacing a registered client.");
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException("The client is invalid.", errors);
        }
        updateFlow(TenantFlowSection.putClient(name, clientId, clientSecret));
    }

    /** Removes the client {@code name}. Removing one that is not there is not an error. */
    public void removeFlowClient(String name) {
        var errors = new FieldErrors();
        checkFlowName(name, errors);
        if (!errors.isEmpty()) {
            throw new BadRequestException("The client name is invalid.", errors);
        }
        updateFlow(TenantFlowSection.removeClient(name));
    }

    /**
     * Registers or replaces the tool server {@code name}. Its URL is checked for shape and for
     * where it points, not for whether a server answers there: the Agents form lists what the
     * server offers, or that it did not answer. A blank credential keeps the stored one, so a
     * server can be re-pointed without retyping it, and is refused for a server not yet stored.
     */
    public void putFlowMcpServer(String name, TenantFlowMcpServerForm form) {
        var errors = new FieldErrors();
        if (name == null || !TenantFlowSection.MCP_NAME.matcher(name).matches()) {
            errors.addFieldError("name", "Not a valid name: use lowercase letters, digits and dashes,"
                    + " starting with a letter or digit, at most 24 characters.");
        }
        String url = form == null ? null : trimmed(form.url());
        if (url == null) {
            errors.addFieldError("url", "A URL is required.");
        } else {
            String refusal = toolServerUrlRefusal(url);
            if (refusal != null) {
                errors.addFieldError("url", refusal);
            }
        }
        String headerName = form == null ? null : trimmed(form.headerName());
        if (headerName == null) {
            headerName = "Authorization";
        }
        if (!HEADER_NAME.matcher(headerName).matches()) {
            errors.addFieldError("headerName", "Not a header name: letters, digits and the punctuation a"
                    + " header name allows, no spaces.");
        } else if (CLIENTS_OWN_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
            errors.addFieldError("headerName", "That header is set by the platform's client; the credential"
                    + " needs another, such as Authorization or X-Api-Key.");
        }
        // Not trimmed: the credential is whatever the server issued, and only blank means absent.
        String headerValue = form == null || form.headerValue() == null || form.headerValue().isBlank()
                ? null : form.headerValue();
        if (headerValue != null && (headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0)) {
            errors.addFieldError("headerValue", "The value must be a single line.");
        }
        String orgName = currentTenant().getOrganizationName();
        if (headerValue == null && errors.isEmpty()
                && !TenantFlowSection.mcpServers(configWriter.current(orgName)).containsKey(name)) {
            errors.addFieldError("headerValue", "A value is required for a new server; leaving it empty"
                    + " keeps the stored one, and nothing is stored under this name.");
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException("The tool server is invalid.", errors);
        }
        updateFlow(TenantFlowSection.putMcpServer(name, url, headerName, headerValue));
    }

    /** Removes the tool server {@code name}, credential included. Removing one that is not there is not an error. */
    public void removeFlowMcpServer(String name) {
        if (name == null || !TenantFlowSection.MCP_NAME.matcher(name).matches()) {
            var errors = new FieldErrors();
            errors.addFieldError("name", "Not a valid name: use lowercase letters, digits and dashes,"
                    + " starting with a letter or digit, at most 24 characters.");
            throw new BadRequestException("The tool server name is invalid.", errors);
        }
        updateFlow(TenantFlowSection.removeMcpServer(name));
    }

    /**
     * Why a tool server's URL may not be stored, or null. The platform's services will call it, so
     * it may not point into their own networks unless the deployment says so; and a credential
     * travels with every call, so plain http is accepted only on such a deployment, where the
     * server sits beside the platform.
     */
    private String toolServerUrlRefusal(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "Not an http(s) URL.";
        }
        String refusal = OutboundUrls.refuse(uri, allowPrivateNetworks);
        if (refusal != null) {
            return refusal;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowPrivateNetworks) {
            return "Use https: the credential travels with every call, and plain http sends it in the clear.";
        }
        return null;
    }

    /**
     * Writes one flow change. Answers nothing: write does not imply read, so a caller who may only
     * write learns no names from it. No cache refresh either: the tenant cache holds no flow keys,
     * and the flow service reads them from Vault when a run starts.
     */
    private void updateFlow(UnaryOperator<Map<String, String>> change) {
        String orgName = currentTenant().getOrganizationName();
        TenantFlowSettings written = flowSettings(configWriter.update(orgName, change));
        log.info("Flow settings updated for tenant {} ({} secrets, {} clients, {} tool servers)", orgName,
                written.secrets().size(), written.clients().size(), written.mcpServers().size());
    }

    private static TenantFlowSettings flowSettings(Map<String, String> secret) {
        List<TenantFlowSettings.Client> clients = new ArrayList<>();
        TenantFlowSection.clientIds(secret).forEach((name, clientId) ->
                clients.add(new TenantFlowSettings.Client(name, clientId)));
        List<TenantFlowSettings.McpServer> servers = new ArrayList<>();
        TenantFlowSection.mcpServers(secret).values().forEach(server ->
                servers.add(new TenantFlowSettings.McpServer(server.name(), server.url(), server.headerName())));
        return new TenantFlowSettings(List.copyOf(TenantFlowSection.secretNames(secret)), clients, servers);
    }

    private static void checkFlowName(String name, FieldErrors errors) {
        if (name == null || !TenantFlowSection.NAME.matcher(name).matches()) {
            errors.addFieldError("name", "Not a valid name: use lowercase letters, digits and"
                    + " dashes, starting with a letter or digit, at most 63 characters.");
        }
    }

    /**
     * The model ids an OpenAI-compatible server lists, as suggestions for the model field.
     *
     * <p>Suggestions only, never a check on what may be saved. Hosts differ in what {@code /models}
     * promises: Azure lists base models but is called by deployment name, and gateways accept
     * aliases they do not list. So a server that cannot be asked gives an empty list, not an error.
     *
     * @param apiKey null to list with the stored key, which is used only against the stored base URL
     */
    public List<String> listModels(String baseUrl, String apiKey) {
        String url = trimmed(baseUrl);
        URI uri;
        try {
            uri = url == null ? null : URI.create(url);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null) {
            var errors = new FieldErrors();
            errors.addFieldError("baseUrl", "Not an http(s) URL, e.g. http://localhost:11434/v1");
            throw new BadRequestException("Cannot list models.", errors);
        }
        String key = trimmed(apiKey);
        if (key == null) {
            // The stored key goes only where it is already sent. Otherwise anyone who may edit
            // these settings could read the key by listing models from a server of their own.
            TenantLlm stored = currentTenant().getLlm();
            if (stored != null && url.equals(trimmed(stored.getBaseUrl()))) {
                key = keyOf(stored);
            }
        }
        List<String> served = servedModels(url, key);
        return served == null ? List.of() : served;
    }

    /**
     * The {@code llm.*} section this form means, or a 400 naming every field that is wrong.
     *
     * <p>All problems are collected rather than thrown on the first, so a half-filled form comes
     * back marked up once instead of one field at a time.
     */
    private LlmSection validated(TenantLlmSettingsForm form, TenantLlm existing) {
        var errors = new FieldErrors();

        LlmProvider provider = null;
        String rawProvider = trimmed(form.provider());
        if (rawProvider == null) {
            errors.addFieldError("provider", "A provider is required: "
                    + String.join(" or ", TenantLlmSettings.PROVIDERS));
        } else {
            try {
                provider = LlmProvider.parse(rawProvider);
            } catch (RuntimeException e) {
                errors.addFieldError("provider", "Unknown provider '" + rawProvider
                        + "'. Use " + String.join(" or ", TenantLlmSettings.PROVIDERS) + ".");
            }
        }

        String model = trimmed(form.model());
        if (model == null) {
            errors.addFieldError("model", "A model name is required.");
        }

        // Absent and empty both mean "leave the stored credential alone"; only a value replaces
        // it. Empty used to clear it, which made an untouched form field a destructive act — the
        // field is rendered empty because the key is never sent back, so saving any other change
        // would have wiped it.
        //
        // The stored key is not read here. This tenant comes from a cache up to five minutes old,
        // so writing its copy back would revert a key rotated since. The writer carries it across
        // from the secret it is about to replace instead.
        String submittedKey = trimmed(form.apiKey());
        boolean keepStoredApiKey = submittedKey == null;
        String baseUrl = trimmed(form.baseUrl());

        // The cache is good enough to decide whether a key exists at all: wrong, it costs a
        // needless "needs an API key" or an incomplete config, never a credential.
        if (provider == LlmProvider.ANTHROPIC && submittedKey == null && keyOf(existing) == null) {
            errors.addFieldError("apiKey", "Anthropic needs an API key.");
        }
        if (provider == LlmProvider.OPENAI_COMPATIBLE && baseUrl == null) {
            errors.addFieldError("baseUrl",
                    "An OpenAI-compatible provider needs a base URL, e.g. http://localhost:11434/v1");
        }

        String effort = trimmed(form.effort());
        if (effort != null && !TenantLlmSettings.EFFORT_LEVELS.contains(effort.toLowerCase())) {
            errors.addFieldError("effort", "Unknown effort level '" + effort + "'. Use one of "
                    + String.join(", ", TenantLlmSettings.EFFORT_LEVELS) + ".");
        }

        String turnTimeout = trimmed(form.turnTimeout());
        if (turnTimeout != null) {
            try {
                DurationStyle.detectAndParse(turnTimeout);
            } catch (IllegalArgumentException e) {
                errors.addFieldError("turnTimeout",
                        "Not a duration: '" + turnTimeout + "'. Try 10m, 90s or PT10M.");
            }
        }

        if (form.maxOutputTokens() != null && form.maxOutputTokens() < 1) {
            errors.addFieldError("maxOutputTokens", "Must be a positive number, or left empty.");
        }
        if (form.maxIterations() != null && form.maxIterations() < 1) {
            errors.addFieldError("maxIterations", "Must be a positive number, or left empty.");
        }

        if (!errors.isEmpty()) {
            throw new BadRequestException("The LLM settings are invalid.", errors);
        }

        Map<String, String> section = new LinkedHashMap<>();
        section.put("provider", provider.wireName());
        section.put("model", model);
        section.put("api-key", submittedKey);
        section.put("base-url", baseUrl);
        section.put("reasoning-effort", trimmed(form.reasoningEffort()));
        section.put("effort", effort == null ? null : effort.toLowerCase());
        section.put("turn-timeout", turnTimeout);
        section.put("max-output-tokens", asString(form.maxOutputTokens()));
        section.put("max-iterations", asString(form.maxIterations()));
        section.put("instructions", trimmed(form.instructions()));
        return new LlmSection(section, keepStoredApiKey);
    }

    /**
     * The model ids an OpenAI-compatible server lists at {@code <baseUrl>/models}, or null when it
     * could not be asked.
     *
     * <p>Nothing the server returned beyond the ids reaches the caller, not even its status: the URL
     * is the tenant's own, and this must not become a way to read responses from hosts only the api
     * can see.
     */
    private List<String> servedModels(String baseUrl, String apiKey) {
        try {
            String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + "/models"))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            if (apiKey != null) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("Cannot list models: {}/models answered HTTP {}", root,
                        response.statusCode());
                return null;
            }
            JsonNode data = jsonMapper.readTree(response.body()).path("data");
            if (!data.isArray()) {
                log.info("Cannot list models: {}/models returned no model list", root);
                return null;
            }
            List<String> ids = new ArrayList<>();
            data.forEach(entry -> {
                String id = entry.path("id").asString(null);
                if (id != null) {
                    ids.add(id);
                }
            });
            return ids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.info("Cannot list models at {}: {}", baseUrl,
                    e.getMessage());
            return null;
        }
    }

    /** The section to write, and whether the writer must carry the stored credential across. */
    private record LlmSection(Map<String, String> keys, boolean keepStoredApiKey) {
    }

    private static String keyOf(TenantLlm existing) {
        return existing == null ? null : trimmed(existing.getApiKey());
    }

    private static String asString(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private Tenant currentTenant() {
        Tenant tenant = tenantConfigService.getConfig(TenantContext.getTenantId());
        if (tenant == null) {
            throw new IllegalStateException("No tenant configuration for " + TenantContext.getTenantId());
        }
        return tenant;
    }
}
