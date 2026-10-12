// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.definition.ConfiguredValue;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.InvalidFlowException;
import ai.intellistream.datahub.flow.engine.CompiledFlow;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RecordSink;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.engine.RunRequest;
import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.flow.engine.RunResult.LogLine;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.runs.RunCredentials.RunIdentityException;
import ai.intellistream.datahub.flow.runs.RunRepository.Claimed;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Executes one claimed attempt: compiles the flow version it runs (once per version, since versions
 * never change), gives it a DataHub client acting as the run's identity behind the lease fence, and
 * hands it to the engine.
 *
 * <p><b>The fence.</b> A processor cannot be interrupted while it waits on DataHub, so stopping a
 * run whose lease is gone is not enough: a write it already has in hand must not go out either. The
 * run's client asks {@link ActiveRun#leaseCovers} before every request — including requests the
 * SDK's ingest makes on its own threads — and sends nothing once the lease is lost or has less left
 * than the request could take ({@code datahub.flow.runs.request-timeout}, which is also every
 * request's timeout). So nothing a fenced run does afterwards reaches DataHub.
 */
@Slf4j
@Component
public class RunExecution {

    /** The run's definition did not compile, for example after a processor was removed. */
    public static final String INVALID_DEFINITION = "INVALID_DEFINITION";
    private static final int MAX_COMPILED = 256;

    private final FlowCompiler compiler;
    private final RunExecutor executor;
    private final FlowRepository flows;
    private final JdbcTemplate jdbc;
    private final RunCredentials credentials;
    private final Function<String, Map<String, String>> tenantSecrets;
    private final String apiBaseUrl;
    private final Duration requestTimeout;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Compiled versions by tenant, flow and version; a definition is never compiled twice. */
    private final Map<String, CompiledFlow> compiled = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CompiledFlow> eldest) {
                    return size() > MAX_COMPILED;
                }
            });

    @Autowired
    public RunExecution(FlowCompiler compiler, RunExecutor executor, FlowRepository flows,
                        JdbcTemplate jdbc, RunCredentials credentials, TenantConfigService tenants,
                        TenantConfigWriter tenantConfig,
                        @Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl,
                        @Value("${datahub.flow.runs.request-timeout:PT30S}") Duration requestTimeout,
                        JsonMapper json) {
        this(compiler, executor, flows, jdbc, credentials, tenantId -> {
            // Read when the run starts, never cached: a secret changed a moment ago is the one used.
            Tenant tenant = tenants.getConfig(tenantId);
            return tenantConfig.current(tenant.getOrganizationName());
        }, apiBaseUrl, requestTimeout, json);
    }

    RunExecution(FlowCompiler compiler, RunExecutor executor, FlowRepository flows, JdbcTemplate jdbc,
                 RunCredentials credentials, Function<String, Map<String, String>> tenantSecrets,
                 String apiBaseUrl, Duration requestTimeout, JsonMapper json) {
        this.compiler = compiler;
        this.executor = executor;
        this.flows = flows;
        this.jdbc = jdbc;
        this.credentials = credentials;
        this.tenantSecrets = tenantSecrets;
        this.apiBaseUrl = apiBaseUrl;
        this.requestTimeout = requestTimeout;
        this.json = json;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    /** Runs the attempt on the calling thread, under the run's tenant's {@code TenantContext}. */
    RunResult execute(ActiveRun run) {
        Claimed claimed = run.claimed;
        CompiledFlow flow;
        try {
            flow = compile(run.tenantId, claimed);
        } catch (InvalidFlowException e) {
            return failed(INVALID_DEFINITION, "The flow version no longer compiles: "
                    + e.problems().getFirst().message());
        }

        Supplier<String> tokens;
        try {
            tokens = credentials.tokens(run.tenantId, claimed.runAs());
        } catch (RunIdentityException e) {
            return failed(RunCoordinator.AUTH, e.getMessage());
        }
        Supplier<String> fenced = () -> {
            if (!run.leaseCovers(requestTimeout)) {
                run.stop(ActiveRun.Stop.LEASE_LOST);
                throw new IllegalStateException("Run " + claimed.id() + " no longer holds its lease; "
                        + "nothing more is sent to DataHub.");
            }
            return tokens.get();
        };
        DatahubClient datahub = DatahubClient.create(DatahubConfig.builder()
                .baseUrl(apiBaseUrl)
                .tokenSupplier(fenced)
                .requestTimeout(requestTimeout)
                .build(), http);

        // Vault is read only by a run that refers to a secret, once.
        AtomicReference<Map<String, String>> secrets = new AtomicReference<>();
        Function<String, String> secret = name -> TenantFlowSection.secretValue(
                secrets.updateAndGet(s -> s != null ? s : tenantSecrets.apply(run.tenantId)), name);
        Long dataSetId = claimed.flowId() == null ? null : jdbc.queryForObject(
                "SELECT data_set_id FROM flow.flow WHERE id = ?", Long.class, claimed.flowId());

        RunRequest request = new RunRequest(claimed.id(), claimed.attempt(), claimed.window(),
                parameters(claimed.parameters()), claimed.preview(), claimed.memoryBytes(), secret,
                run::shouldStop, dataSetId, datahub);
        // The records go nowhere yet: the next slice publishes them to the tenant's records topic
        // and waits for Pulsar to acknowledge the last batch before the run may finish.
        RecordSink records = record -> { };
        return executor.execute(flow, request, records);
    }

    private CompiledFlow compile(String tenantId, Claimed claimed) {
        if (claimed.flowId() == null) {
            return compiler.compile(FlowDefinitions.parse(claimed.definition()));
        }
        String key = tenantId + "/" + claimed.flowId() + "/" + claimed.version();
        CompiledFlow flow = compiled.get(key);
        if (flow == null) {
            String definition = flows.version(claimed.flowId(), claimed.version()).orElseThrow()
                    .definition();
            flow = compiler.compile(FlowDefinitions.parse(definition));
            compiled.put(key, flow);
        }
        return flow;
    }

    private Map<String, Object> parameters(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        JsonNode node = json.readTree(text);
        node.properties().forEach(e -> out.put(e.getKey(), ConfiguredValue.fromJson(e.getValue())));
        return out;
    }

    private static RunResult failed(String code, String error) {
        return new RunResult(RunResult.Status.FAILED, code, error, null, Map.of(), Set.of(), Map.of(),
                List.of(new LogLine(Instant.now(Clock.systemUTC()), null, "ERROR", error)), 0);
    }
}
