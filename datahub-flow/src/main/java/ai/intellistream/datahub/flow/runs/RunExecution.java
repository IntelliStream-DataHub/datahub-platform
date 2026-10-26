// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.definition.ConfiguredValue;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.InvalidFlowException;
import ai.intellistream.datahub.flow.engine.CompiledFlow;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.engine.RunRequest;
import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.flow.engine.RunResult.LogLine;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.records.RunRecords;
import ai.intellistream.datahub.flow.runs.RunCredentials.RunIdentityException;
import ai.intellistream.datahub.flow.store.RunStore.Claimed;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 *
 * <p><b>The records.</b> What the engine records goes to the tenant's records topic as the run goes
 * ({@link RunRecords}), and the attempt is not finished until Pulsar has acknowledged all of it: a
 * run is never {@code SUCCEEDED} with records only in memory. The wait is the run's remaining time,
 * but at least {@code datahub.flow.records.ack-wait}, so a run that used its time up still gets its
 * records out; one whose records cannot be published fails with {@code RECORDS_UNPUBLISHED}.
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
    private final RunCredentials credentials;
    private final Function<String, Map<String, String>> tenantSecrets;
    private final String apiBaseUrl;
    private final Duration requestTimeout;
    private final JsonMapper json;
    private final RunRecords.Opener records;
    private final Duration ackWait;
    /** What every run may hold on the heap: this instance's share per run slot (FlowConfig). */
    private final long memoryCap;
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
                        RunCredentials credentials, TenantConfigService tenants,
                        TenantConfigWriter tenantConfig,
                        @Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl,
                        @Value("${datahub.flow.runs.request-timeout:PT30S}") Duration requestTimeout,
                        JsonMapper json, RunRecords.Opener records,
                        @Value("${datahub.flow.records.ack-wait:PT30S}") Duration ackWait,
                        DataSize runMemoryCap) {
        this(compiler, executor, flows, credentials, tenantId -> {
            // Read when the run starts, never cached: a secret changed a moment ago is the one used.
            Tenant tenant = tenants.getConfig(tenantId);
            return tenantConfig.current(tenant.getOrganizationName());
        }, apiBaseUrl, requestTimeout, json, records, ackWait, runMemoryCap.toBytes());
    }

    RunExecution(FlowCompiler compiler, RunExecutor executor, FlowRepository flows,
                 RunCredentials credentials, Function<String, Map<String, String>> tenantSecrets,
                 String apiBaseUrl, Duration requestTimeout, JsonMapper json, RunRecords.Opener records,
                 Duration ackWait, long memoryCap) {
        this.memoryCap = memoryCap;
        this.compiler = compiler;
        this.executor = executor;
        this.flows = flows;
        this.credentials = credentials;
        this.tenantSecrets = tenantSecrets;
        this.apiBaseUrl = apiBaseUrl;
        this.requestTimeout = requestTimeout;
        this.json = json;
        this.records = records;
        this.ackWait = ackWait;
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

        // A preview acts as the person previewing, with the token they called with; every other run
        // as its flow's deployed identity.
        Supplier<String> tokens;
        try {
            if (run.tokens != null) {
                tokens = run.tokens;
            } else if (claimed.flowId() == null) {
                throw new RunIdentityException("A preview of an unsaved definition has no "
                        + "deployment to act as.", true);
            } else {
                tokens = credentials.tokens(run.tenantId, claimed.flowId());
            }
        } catch (RunIdentityException e) {
            return failed(e.rejected() ? RunCoordinator.IDENTITY_REJECTED : RunCoordinator.AUTH,
                    e.getMessage());
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
        // What the run was made with, as the run store holds it: its flow's external id and dataset.
        Long dataSetId = claimed.dataSetId();
        String flowExternalId = claimed.flowExternalId();
        if (dataSetId != null && dataSetDeleted(datahub, dataSetId)) {
            if (claimed.flowId() != null) {
                flows.disable(claimed.flowId(), RunCoordinator.DATASET_DELETED);
            }
            log.warn("Flow {}'s dataset {} is gone: the flow is switched off", claimed.flowId(), dataSetId);
            return failed(RunCoordinator.DATASET_DELETED, "The flow's dataset no longer exists, so the "
                    + "flow was switched off. Move it to another dataset or delete it.");
        }

        RunRequest request = new RunRequest(claimed.id(), claimed.attempt(), claimed.window(),
                parameters(claimed.parameters()), claimed.preview(), memoryCap, secret,
                run::shouldStop, dataSetId, datahub, fenced);
        RunRecords sink = records.open(run.tenantId, claimed.id(), claimed.attempt(),
                flowExternalId == null ? "" : flowExternalId,
                claimed.version() == null ? 0 : claimed.version(), dataSetId);
        RunResult result = executor.execute(flow, request, sink);
        Duration left = run.timeLeft();
        try {
            sink.finish(left.compareTo(ackWait) > 0 ? left : ackWait);
        } catch (RunRecords.UnpublishedException e) {
            log.warn("Run {} attempt {}: {}", claimed.id(), claimed.attempt(), e.getMessage());
            return unpublished(result, e.getMessage());
        }
        return result;
    }

    /**
     * A run whose records did not reach Pulsar: failed, keeping what it ran with. A run that had
     * already failed keeps its own error, with the records' failure added to its log.
     */
    private static RunResult unpublished(RunResult result, String error) {
        List<LogLine> log = new ArrayList<>(result.log());
        log.add(new LogLine(Instant.now(Clock.systemUTC()), null, "ERROR", error));
        boolean succeeded = result.status() == RunResult.Status.SUCCEEDED;
        return new RunResult(succeeded ? RunResult.Status.FAILED : result.status(),
                succeeded ? RunCoordinator.RECORDS_UNPUBLISHED : result.errorCode(),
                succeeded ? error : result.error(), result.failedProcessor(), result.parameters(),
                result.secretsUsed(), result.processorVersions(), List.copyOf(log), result.records());
    }

    /**
     * Whether the flow's dataset was deleted: one lookup per run, as the run's identity. A deleted
     * dataset is simply absent (datasets are hard-deleted), and dataset reads are not narrowed by
     * access, so absence means gone. When the api cannot answer the run goes ahead; its own calls
     * then fail or succeed on their own terms.
     */
    private static boolean dataSetDeleted(DatahubClient datahub, long dataSetId) {
        try {
            var items = datahub.datasets().byIds(List.of(IdCollection.createFromId(dataSetId))).getItems();
            return items == null || items.isEmpty();
        } catch (DatahubApiException e) {
            log.warn("Could not check that dataset {} still exists: {}", dataSetId, e.getMessage());
            return false;
        }
    }

    private CompiledFlow compile(String tenantId, Claimed claimed) {
        if (claimed.flowId() == null) {
            return compiler.compile(FlowDefinitions.parse(claimed.definition()));
        }
        String key = tenantId + "/" + claimed.flowId() + "/" + claimed.version();
        CompiledFlow flow = compiled.get(key);
        if (flow == null) {
            String definition = flows.version(claimed.flowId(), claimed.version()).orElseThrow(() ->
                    new IllegalStateException("Flow " + claimed.flowId() + " has no version " + claimed.version()
                            + " in the database of tenant " + tenantId + ": the run store holds a run this "
                            + "tenant's database does not know"))
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
