// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.chat.config.ChatSettings;
import ai.intellistream.dhconsole.chat.config.ChatSettings.BackendKey;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One model client per credential, built on first use.
 *
 * <p>A cache rather than a bean because the Anthropic SDK binds the credential at construction, so
 * "which client" is a runtime question once tenants have their own keys. Keyed on the connection
 * identity and not the model, so tenants sharing a credential share a connection pool.
 *
 * <p>A rotated key is simply a new entry; {@link #evictUnusedBackends()} sweeps what nothing names
 * any more. Every credential here belongs to a tenant — there is no deployment-wide one — so a
 * tenant that removes its configuration has its client swept along with its panel.
 */
@Slf4j
public class LlmBackends implements LlmClients {

    private final TenantConfigService tenantConfigService;
    private final JsonMapper json;

    private final Map<BackendKey, Entry> clients = new ConcurrentHashMap<>();

    public LlmBackends(TenantConfigService tenantConfigService, JsonMapper json) {
        this.tenantConfigService = tenantConfigService;
        this.json = json;
    }

    @Override
    public LlmClient forSettings(ChatSettings settings) {
        return clients.computeIfAbsent(settings.backendKey(), this::build).client();
    }

    private Entry build(BackendKey key) {
        return switch (key.provider()) {
            case ANTHROPIC -> {
                if (key.apiKey() == null || key.apiKey().isBlank()) {
                    throw new IllegalStateException(
                            "No Anthropic API key is configured. Set llm.api-key on this tenant's "
                                    + "tenant-config/<org-name> secret.");
                }
                AnthropicClient sdk = AnthropicOkHttpClient.builder().apiKey(key.apiKey()).build();
                log.info("Chat backend ready: anthropic");
                yield new Entry(new AnthropicLlmClient(sdk, json), sdk);
            }
            case OPENAI_COMPATIBLE -> {
                log.info("Chat backend ready: openai-compatible at {}", key.baseUrl());
                yield new Entry(new OpenAiCompatibleLlmClient(key.baseUrl(), key.apiKey(), json), null);
            }
        };
    }

    /** Hygiene, not correctness: a new credential is a new entry whether this ever runs or not. */
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

    /**
     * Every credential some tenant currently names.
     *
     * <p>Must key exactly as {@link ChatSettings#backendKey()} does, or a client in use keys
     * differently here and gets swept out from under a live turn.
     */
    private Set<BackendKey> liveKeys() {
        Set<BackendKey> live = new HashSet<>();
        for (Tenant tenant : tenantConfigService.cachedTenants.values()) {
            TenantLlm llm = tenant.getLlm();
            if (llm != null && llm.isUsable()) {
                live.add(new BackendKey(llm.getProvider(), strip(llm.getApiKey()), strip(llm.getBaseUrl())));
            }
        }
        return live;
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Visible for tests: how many distinct backends are currently held. */
    int size() {
        return clients.size();
    }

    /** The OpenAI-compatible client is on the JDK HttpClient, which needs no closing — hence null. */
    private record Entry(LlmClient client, AnthropicClient closeable) {
        void close() {
            if (closeable != null) {
                closeable.close();
            }
        }
    }
}
