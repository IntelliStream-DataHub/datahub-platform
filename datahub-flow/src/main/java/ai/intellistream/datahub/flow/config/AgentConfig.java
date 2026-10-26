// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.processors.Agent;
import ai.intellistream.datahub.flow.store.AgentTranscripts;
import ai.intellistream.datahub.llm.LlmBackends;
import ai.intellistream.datahub.llm.mcp.StatelessMcpClient;
import ai.intellistream.datahub.tenant.TenantConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the agent processor: the model clients, built per tenant credential on first use as the
 * console's chat does ({@link LlmBackends}), and the platform's MCP servers, datahub-api always and
 * datahub-analysis when {@code datahub.analysis.url} names it.
 *
 * <p>The sweep of unused model clients is scheduled here, as in the console; it is a map walk, so it
 * costs the service's one scheduler thread nothing worth measuring.
 */
@Slf4j
@Configuration
public class AgentConfig {

    private final LlmBackends backends;

    public AgentConfig(TenantConfigService tenants, JsonMapper json) {
        this.backends = new LlmBackends(tenants, json);
    }

    @Bean
    Agent agent(AgentTranscripts transcripts, AgentProperties properties, TenantConfigService tenants,
                JsonMapper json, @Value("${datahub.api.url:http://localhost:8081}") String apiUrl,
                @Value("${datahub.analysis.url:}") String analysisUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<StatelessMcpClient> servers = new ArrayList<>();
        servers.add(new StatelessMcpClient("datahub-api", apiUrl, http, json));
        if (analysisUrl != null && !analysisUrl.isBlank()) {
            servers.add(new StatelessMcpClient("datahub-analysis", analysisUrl, http, json));
        }
        log.info("Agents wired with the tools of {}. Each tenant supplies its own model via its llm settings.",
                servers.stream().map(StatelessMcpClient::name).toList());
        return new Agent(backends, servers, transcripts, properties, tenants, Clock.systemUTC());
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void evictUnusedAgentBackends() {
        backends.evictUnusedBackends();
    }
}
