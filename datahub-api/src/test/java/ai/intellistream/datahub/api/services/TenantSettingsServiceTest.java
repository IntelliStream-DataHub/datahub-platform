// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
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
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation and the credential's one-way trip.
 *
 * <p>These settings go to Vault, which accepts any string, and are read by a different process that
 * cannot answer back. A tenant that saves nonsense finds out when its assistant stops working, so
 * everything checkable is checked here, before the write.
 */
class TenantSettingsServiceTest {

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    private TenantConfigService tenantConfigService;
    private TenantConfigWriter writer;
    private TenantSettingsService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setOrganizationId(ORG_ID);
        tenant.setOrganizationName("acme");

        tenantConfigService = mock(TenantConfigService.class);
        when(tenantConfigService.getConfig(anyString())).thenReturn(tenant);
        writer = mock(TenantConfigWriter.class);
        service = new TenantSettingsService(tenantConfigService, writer, JsonMapper.builder().build(), false);

        TenantContext.setTenantId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        if (modelServer != null) {
            modelServer.stop(0);
        }
    }

    private HttpServer modelServer;

    /** A model server answering {@code GET /v1/models} with this status and body; returns its base URL. */
    private String modelServer(int status, String body) throws IOException {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/v1/models", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        modelServer.start();
        return "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1";
    }

    private static TenantLlm anthropic(String key) {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.ANTHROPIC);
        llm.setModel("claude-opus-5");
        llm.setApiKey(key);
        return llm;
    }

    private Map<String, String> written() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> section = ArgumentCaptor.forClass(Map.class);
        verify(writer).writeLlmSection(org.mockito.ArgumentMatchers.eq("acme"), section.capture(),
                org.mockito.ArgumentMatchers.anyBoolean());
        return section.getValue();
    }

    /** Whether the write was told to carry the stored credential across. */
    private boolean keptStoredKey() {
        ArgumentCaptor<Boolean> keep = ArgumentCaptor.forClass(Boolean.class);
        verify(writer).writeLlmSection(org.mockito.ArgumentMatchers.eq("acme"),
                org.mockito.ArgumentMatchers.anyMap(), keep.capture());
        return keep.getValue();
    }

    @Test
    void theCredentialIsNeverReadBack() {
        // The whole reason the response record has no apiKey field. A test here rather than only
        // on the record because this is the method that would have to leak it.
        tenant.setLlm(anthropic("sk-ant-secret"));

        TenantLlmSettings settings = service.readLlm();

        assertThat(settings.apiKeySet()).isTrue();
        assertThat(settings.toString()).doesNotContain("sk-ant-secret");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
        assertThat(settings.configured()).isTrue();
    }

    @Test
    void anUnconfiguredTenantReadsAsUnconfiguredRatherThanFailing() {
        tenant.setLlm(null);

        assertThat(service.readLlm()).isEqualTo(TenantLlmSettings.none());
    }

    /**
     * The credential survives a save that does not mention it, whatever "does not mention it"
     * looks like on the wire.
     *
     * <p>This is the property the apiKey handling exists to protect, and the one that is silently
     * destructive when it breaks. The key is never sent back to a client, so the field is rendered
     * empty every time the form loads; if empty meant "clear", then changing the model — or the
     * timeout, or anything else — would delete the credential as a side effect, and the only
     * symptom would be an assistant that stopped answering.
     *
     * <p>Preserved by asking the writer to keep what the secret holds, never by sending a key: see
     * {@link #theCachedCredentialIsNeverWrittenBack}.
     */
    @Test
    void aSaveThatDoesNotSupplyAKeyLeavesTheStoredOneAlone() {
        for (String submitted : new String[] {null, "", "   ", "\t"}) {
            org.mockito.Mockito.reset(writer);
            tenant.setLlm(anthropic("sk-ant-stored"));

            service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-sonnet-5", submitted,
                    null, null, null, null, null, null, null));

            assertThat(keptStoredKey())
                    .as("apiKey=%s must not disturb the stored credential", describe(submitted))
                    .isTrue();
            assertThat(written()).containsEntry("model", "claude-sonnet-5");
        }
    }

    /**
     * The stale copy in the tenant cache never reaches Vault.
     *
     * <p>The registry this reads is refreshed on a five-minute timer, so its key can be older than
     * the secret's. Writing it back would revert a key rotated in between, whether from another api
     * instance or by hand, and compare-and-set could not catch it: the version would not have moved
     * between the writer's own read and its write. So the service sends no key at all and the
     * writer carries the current one across.
     */
    @Test
    void theCachedCredentialIsNeverWrittenBack() {
        tenant.setLlm(anthropic("sk-ant-stale"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-sonnet-5", null,
                null, null, null, null, null, null, null));

        assertThat(written()).doesNotContainValue("sk-ant-stale");
        assertThat(keptStoredKey()).isTrue();
    }

    /** The same, for a provider whose key is optional — an absent key must not delete a stored one. */
    @Test
    void anOptionalKeyIsAlsoLeftAloneWhenNotSupplied() {
        TenantLlm onprem = new TenantLlm();
        onprem.setProvider(LlmProvider.OPENAI_COMPATIBLE);
        onprem.setModel("qwen3-32b");
        onprem.setBaseUrl("http://127.0.0.1:1/v1");
        onprem.setApiKey("gateway-token");
        tenant.setLlm(onprem);

        service.updateLlm(new TenantLlmSettingsForm("openai-compatible", "qwen3-32b", "",
                "http://127.0.0.1:1/v1", null, null, null, null, null, null));

        assertThat(keptStoredKey()).isTrue();
        assertThat(written()).doesNotContainValue("gateway-token");
    }

    @Test
    void aSuppliedKeyReplacesTheStoredOne() {
        tenant.setLlm(anthropic("sk-ant-old"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-opus-5", "  sk-ant-new  ",
                null, null, null, null, null, null, null));

        assertThat(written()).containsEntry("api-key", "sk-ant-new");
        assertThat(keptStoredKey()).isFalse();
    }

    private static String describe(String value) {
        return value == null ? "absent" : "\"" + value + "\"";
    }

    @Test
    void aProviderIsRefusedWithoutWhatItNeedsToReachAModel() {
        tenant.setLlm(null);

        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "anthropic", "claude-opus-5", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "openai-compatible", "qwen3-32b", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);

        verify(writer, never()).writeLlmSection(anyString(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void everyBadFieldIsReportedAtOnce() {
        // A half-filled form should come back marked up once, not one field per round trip.
        tenant.setLlm(null);

        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "telepathy", null, null, null, null, "ludicrous", "soon", -1, 0, null)))
                .isInstanceOfSatisfying(BadRequestException.class, failure -> {
                    var names = failure.getFields().stream()
                            .map(field -> field.field()).toList();
                    assertThat(names).contains("provider", "model", "effort", "turnTimeout",
                            "maxOutputTokens", "maxIterations");
                });
    }

    @Test
    void bothDurationSpellingsAreAccepted() {
        tenant.setLlm(anthropic("k"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-opus-5", null, null,
                null, null, "10m", null, null, null));
        assertThat(written()).containsEntry("turn-timeout", "10m");
    }

    @Test
    void theWriteCarriesOnlyTheSectionAndTheCacheIsReloaded() {
        // The reload is what makes this instance answer with what it just stored rather than the
        // five-minute-old cache it read the form from.
        tenant.setLlm(anthropic("k"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-opus-5", null, null,
                null, "max", null, 64_000, 20, "House style."));

        assertThat(written())
                .containsEntry("provider", "anthropic")
                .containsEntry("effort", "max")
                .containsEntry("max-output-tokens", "64000")
                .containsEntry("max-iterations", "20")
                .containsEntry("instructions", "House style.");
        verify(tenantConfigService).refreshCache();
    }

    /**
     * Any name saves, listed or not: some hosts are called by names their {@code /models} leaves
     * out, such as Azure deployment names or gateway aliases.
     */
    @Test
    void aModelTheServerDoesNotListIsStillSaved() throws IOException {
        tenant.setLlm(null);
        String baseUrl = modelServer(200, "{\"data\":[{\"id\":\"gpt-4o\"}]}");

        service.updateLlm(new TenantLlmSettingsForm("openai-compatible", "my-azure-deployment",
                null, baseUrl, null, null, null, null, null, null));

        assertThat(written()).containsEntry("model", "my-azure-deployment");
    }

    @Test
    void theServersModelsAreListed() throws IOException {
        tenant.setLlm(null);
        String baseUrl = modelServer(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3.8:latest\"},{\"id\":\"nemotron:30b\"}]}");

        assertThat(service.listModels(baseUrl, null)).containsExactly("qwen3.8:latest", "nemotron:30b");
    }

    /** Nothing from a server that failed reaches the caller, not even that it failed. */
    @Test
    void aServerThatCannotBeAskedListsNothing() throws IOException {
        tenant.setLlm(null);

        assertThat(service.listModels(modelServer(500, "internal detail"), null)).isEmpty();
        assertThat(service.listModels("http://127.0.0.1:1/v1", null)).isEmpty();
    }

    @Test
    void listingNeedsAnHttpUrl() {
        assertThatThrownBy(() -> service.listModels("file:///etc/passwd", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.listModels(null, null))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * The stored key is sent only to the server it is already sent to. Otherwise anyone who may
     * edit these settings could read it by listing models from a server of their own.
     */
    @Test
    void theStoredKeyGoesOnlyToTheStoredServer() throws IOException {
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        modelServer.start();
        String baseUrl = "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1";

        TenantLlm stored = new TenantLlm();
        stored.setProvider(LlmProvider.OPENAI_COMPATIBLE);
        stored.setModel("qwen3-32b");
        stored.setBaseUrl("http://vllm.internal:8000/v1");
        stored.setApiKey("gateway-token");
        tenant.setLlm(stored);

        service.listModels(baseUrl, null);
        assertThat(authorization.get()).isNull();

        stored.setBaseUrl(baseUrl);
        service.listModels(baseUrl, null);
        assertThat(authorization.get()).isEqualTo("Bearer gateway-token");
    }

    /** Runs each flow change against {@code stored}, as Vault would, and keeps the result. */
    @SuppressWarnings("unchecked")
    private Map<String, String> vaultHolding(Map<String, String> initial) {
        Map<String, String> stored = new HashMap<>(initial);
        when(writer.update(eq("acme"), any())).thenAnswer(invocation -> {
            Map<String, String> changed = ((UnaryOperator<Map<String, String>>)
                    invocation.getArgument(1)).apply(Map.copyOf(stored));
            stored.clear();
            stored.putAll(changed);
            return Map.copyOf(stored);
        });
        when(writer.current("acme")).thenAnswer(invocation -> Map.copyOf(stored));
        return stored;
    }

    @Test
    void flowCredentialsAreListedByNameAndNeverByValue() {
        vaultHolding(Map.of(
                "llm.api-key", "sk-ant-stored",
                "flow.secret.sftp-password", "hunter2",
                "flow.client.ingest.client-id", "datahub-ingest",
                "flow.client.ingest.client-secret", "client-secret-value",
                "flow.token.42", "offline-token-value"));

        TenantFlowSettings settings = service.readFlow();

        assertThat(settings.secrets()).containsExactly("sftp-password");
        assertThat(settings.clients())
                .containsExactly(new TenantFlowSettings.Client("ingest", "datahub-ingest"));
        assertThat(settings.toString())
                .doesNotContain("hunter2", "client-secret-value", "offline-token-value", "sk-ant");
    }

    @Test
    void severalClientsAndSecretsLiveSideBySide() {
        Map<String, String> stored = vaultHolding(Map.of("flow.token.7", "offline"));

        service.putFlowClient("ingest", new TenantFlowClientForm("datahub-ingest", "s1"));
        service.putFlowClient("reporting", new TenantFlowClientForm("datahub-reporting", "s2"));
        service.putFlowSecret("a", new TenantFlowSecretForm("v1"));
        service.putFlowSecret("b", new TenantFlowSecretForm("v2"));
        TenantFlowSettings settings = service.readFlow();

        assertThat(settings.secrets()).containsExactly("a", "b");
        assertThat(settings.clients()).extracting(TenantFlowSettings.Client::name)
                .containsExactly("ingest", "reporting");
        assertThat(stored).containsEntry("flow.client.reporting.client-secret", "s2")
                .containsEntry("flow.token.7", "offline");

        service.removeFlowClient("ingest");
        settings = service.readFlow();
        assertThat(settings.clients()).extracting(TenantFlowSettings.Client::name)
                .containsExactly("reporting");
        assertThat(stored).doesNotContainKeys("flow.client.ingest.client-id",
                "flow.client.ingest.client-secret");
    }

    @Test
    void aFlowWriteIsRefusedWithoutItsRequiredFields() {
        vaultHolding(Map.of());

        assertThatThrownBy(() -> service.putFlowClient("ingest",
                new TenantFlowClientForm("datahub-ingest", " ")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.putFlowSecret("pw", new TenantFlowSecretForm(null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.putFlowSecret("Not.Valid", new TenantFlowSecretForm("x")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.removeFlowClient("../llm"))
                .isInstanceOf(BadRequestException.class);
        verify(writer, never()).update(anyString(), any());
    }

    @Test
    void aToolServerIsRegisteredWithItsCredentialAndListedWithoutIt() {
        Map<String, String> stored = vaultHolding(Map.of("flow.token.7", "offline"));

        service.putFlowMcpServer("crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp ", " ", "Bearer k-1"));

        TenantFlowSettings settings = service.readFlow();
        assertThat(settings.mcpServers())
                .containsExactly(new TenantFlowSettings.McpServer("crm", "https://8.8.8.8/mcp", "Authorization"));
        assertThat(settings.toString()).doesNotContain("k-1");
        assertThat(stored).containsEntry("flow.mcp.crm.header-value", "Bearer k-1")
                .containsEntry("flow.token.7", "offline");

        // Re-pointed with the credential field left empty: the stored one is kept.
        service.putFlowMcpServer("crm", new TenantFlowMcpServerForm("https://8.8.4.4/mcp", "X-Api-Key", ""));
        assertThat(stored).containsEntry("flow.mcp.crm.url", "https://8.8.4.4/mcp")
                .containsEntry("flow.mcp.crm.header-name", "X-Api-Key")
                .containsEntry("flow.mcp.crm.header-value", "Bearer k-1");

        service.removeFlowMcpServer("crm");
        assertThat(service.readFlow().mcpServers()).isEmpty();
        assertThat(stored).containsOnlyKeys("flow.token.7");
    }

    @Test
    void aToolServerIsRefusedWithEverythingThatWouldNotWork() {
        vaultHolding(Map.of());
        record Case(String name, TenantFlowMcpServerForm form, String field) {
        }
        for (Case bad : java.util.List.of(
                new Case("a".repeat(25), new TenantFlowMcpServerForm("https://8.8.8.8/mcp", null, "v"), "name"),
                new Case("Crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp", null, "v"), "name"),
                new Case("crm", new TenantFlowMcpServerForm(null, null, "v"), "url"),
                new Case("crm", new TenantFlowMcpServerForm("ftp://8.8.8.8/", null, "v"), "url"),
                new Case("crm", new TenantFlowMcpServerForm("https://u:p@8.8.8.8/mcp", null, "v"), "url"),
                // The platform's own networks, and plain http, are off limits unless the deployment says so.
                new Case("crm", new TenantFlowMcpServerForm("https://127.0.0.1/mcp", null, "v"), "url"),
                new Case("crm", new TenantFlowMcpServerForm("http://8.8.8.8/mcp", null, "v"), "url"),
                new Case("crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp", "Host", "v"), "headerName"),
                new Case("crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp", "Mcp-Session-Id", "v"), "headerName"),
                new Case("crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp", "bad header", "v"), "headerName"),
                new Case("crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp", null, "two\nlines"), "headerValue"),
                // No credential and none stored under the name.
                new Case("crm", new TenantFlowMcpServerForm("https://8.8.8.8/mcp", null, " "), "headerValue"))) {
            assertThatThrownBy(() -> service.putFlowMcpServer(bad.name(), bad.form()))
                    .as(bad.toString())
                    .isInstanceOfSatisfying(BadRequestException.class, failure ->
                            assertThat(failure.getFields()).extracting(f -> f.field()).contains(bad.field()));
        }
        assertThatThrownBy(() -> service.removeFlowMcpServer("../llm")).isInstanceOf(BadRequestException.class);
        verify(writer, never()).update(anyString(), any());
    }

    @Test
    void privateAddressesAndPlainHttpAreAcceptedWhereTheDeploymentAllowsThem() {
        service = new TenantSettingsService(tenantConfigService, writer, JsonMapper.builder().build(), true);
        Map<String, String> stored = vaultHolding(Map.of());

        service.putFlowMcpServer("local", new TenantFlowMcpServerForm("http://127.0.0.1:9/mcp", null, "k"));

        assertThat(stored).containsEntry("flow.mcp.local.url", "http://127.0.0.1:9/mcp");
    }
}
