// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.config.AgentSettings.BackendKey;
import ai.intellistream.dhconsole.chat.config.ChatProperties;
import com.anthropic.client.AnthropicClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One model client per credential, shared by every agent that uses it.
 *
 * <h3>Why a cache and not a bean</h3>
 * A tenant may bring its own provider, key and endpoint, and no LLM framework supports supplying a
 * credential per request — Spring AI binds the key when the model is built, and the same is true of
 * the vendor SDKs underneath. So "which model client" becomes a runtime lookup rather than a
 * singleton, and something has to own the instances.
 *
 * <p>The key is the connection identity — provider, key, endpoint — and deliberately not the model
 * name, which is a per-request parameter. Two agents pointed at the same credential but different
 * models therefore share one instance, and with it one connection pool, which is the expensive part.
 *
 * <h3>Rotation and eviction</h3>
 * A rotated key produces a different key and therefore a new entry, with no invalidation step to
 * get wrong. What that leaves behind is the old entry, so {@link #evictUnusedBackends()} sweeps
 * anything no live tenant configuration names any more and closes it. {@code TenantConfigService}
 * publishes no event for a configuration *change* — consumers are expected to re-read the swapped
 * map — so this is a sweep rather than a listener.
 *
 * <h3>Failure is per tenant</h3>
 * A tenant whose backend is unusable (Anthropic with no key, an OpenAI-compatible entry with no
 * endpoint) fails its own turn with an operator-facing message. It does not prevent the application
 * starting and it does not affect any other tenant — which is why the credential check that used to
 * run at bean-creation time now runs here.
 */
@Slf4j
public class LlmBackends implements LlmClients {

    private final ChatProperties properties;
    private final TenantConfigService tenantConfigService;
    private final JsonMapper json;

    private final Map<BackendKey, Entry> clients = new ConcurrentHashMap<>();

    public LlmBackends(ChatProperties properties, TenantConfigService tenantConfigService, JsonMapper json) {
        this.properties = properties;
        this.tenantConfigService = tenantConfigService;
        this.json = json;
    }

    /**
     * The client for these settings, built on first use.
     *
     * @throws IllegalStateException if the resolved backend cannot be used at all. The message is
     *                               aimed at whoever configured it, since nobody else can fix it.
     */
    @Override
    public LlmClient forSettings(AgentSettings settings) {
        return clients.computeIfAbsent(settings.backendKey(), this::build).client();
    }

    private Entry build(BackendKey key) {
        LlmProvider provider = key.provider() == null ? properties.getProvider() : key.provider();
        return switch (provider) {
            case ANTHROPIC -> {
                if (key.apiKey() == null || key.apiKey().isBlank()) {
                    throw new IllegalStateException(
                            "No Anthropic API key is configured. Set it on the tenant's llm-backends "
                                    + "entry in Vault, or as the llm.api-key field of the "
                                    + "intellistream-datahub/datahub-console secret for the "
                                    + "deployment default.");
                }
                // Spring AI's own factory rather than AnthropicOkHttpClient: it builds the same
                // SDK client over the transport spring-ai-anthropic ships, so the console needs no
                // second okhttp integration on the classpath. Nulls mean "the SDK's default" for
                // base url, timeout, retries and proxy — the same defaults this used before.
                AnthropicClient sdk = AnthropicSetup.setupSyncClient(
                        null, key.apiKey(), null, null, null, Map.of());
                // Options are supplied per request; this default only exists because the model
                // requires one, and every field on it is overridden by the prompt's own options.
                AnthropicChatModel model = AnthropicChatModel.builder()
                        .anthropicClient(sdk)
                        .options(AnthropicChatOptions.builder().build())
                        .build();
                log.info("Chat backend ready: anthropic");
                yield new Entry(new SpringAiAnthropicLlmClient(model, json), sdk);
            }
            case OPENAI_COMPATIBLE -> {
                log.info("Chat backend ready: openai-compatible at {}", key.baseUrl());
                yield new Entry(new OpenAiCompatibleLlmClient(key.baseUrl(), key.apiKey(), json), null);
            }
        };
    }

    /**
     * Drop clients no configuration names any more, so a rotated or removed credential does not
     * keep a connection pool alive for the life of the process.
     *
     * <p>Ten minutes because the thing being cleaned up is small and the cost of holding it a
     * little longer is a few idle sockets — this is hygiene, not correctness. Correctness is that a
     * new key is a new entry, which happens on the next turn regardless of this sweep.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void evictUnusedBackends() {
        Set<BackendKey> live = liveKeys();
        Iterator<Map.Entry<BackendKey, Entry>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BackendKey, Entry> cached = it.next();
            if (live.contains(cached.getKey())) {
                continue;
            }
            it.remove();
            cached.getValue().close();
            log.info("Released an unused chat backend ({})", cached.getKey().provider());
        }
    }

    /** Every credential some tenant, or the deployment default, currently names. */
    private Set<BackendKey> liveKeys() {
        Set<BackendKey> live = new HashSet<>();
        live.add(new BackendKey(properties.getProvider(), properties.getApiKey(), properties.getBaseUrl()));
        for (Tenant tenant : tenantConfigService.cachedTenants.values()) {
            TenantLlm llm = tenant.getLlm();
            if (llm == null) {
                continue;
            }
            // Mirrors how AgentSettingsResolver fills the gaps, so a tenant that inherits the
            // deployment credential maps to the same key here as it does there.
            live.add(new BackendKey(
                    llm.getProvider() == null ? properties.getProvider() : llm.getProvider(),
                    llm.getApiKey() == null ? properties.getApiKey() : llm.getApiKey(),
                    llm.getBaseUrl() == null ? properties.getBaseUrl() : llm.getBaseUrl()));
        }
        return live;
    }

    /** Visible for tests: how many distinct backends are currently held. */
    int size() {
        return clients.size();
    }

    /**
     * A cached client and, where the SDK has one, the closeable underneath it. The
     * OpenAI-compatible client is built on the JDK's {@code HttpClient}, which needs no closing.
     */
    private record Entry(LlmClient client, AnthropicClient closeable) {
        void close() {
            if (closeable != null) {
                closeable.close();
            }
        }
    }
}
