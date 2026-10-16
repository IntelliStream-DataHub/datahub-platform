// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.runs.RunCredentials.RunIdentityException;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.events.LatestEventsQuery;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code run-failed} lifecycle events of the plan's <em>Failed runs</em>: one lifecycle per flow
 * and window, as DataHub events sharing an {@code externalId} — {@code open} when a run fails after
 * its last attempt, {@code resolved} when a later run of the same window succeeds, and
 * {@code dismissed} when someone dismisses it. The console's <em>Failed runs</em> list is the
 * lifecycles whose latest event is {@code open}, read straight from the events API
 * ({@code POST /events/latest}), so there is no store of notifications here.
 *
 * <p><b>Dismissing</b> is not this service's: the console writes a {@code dismissed} event with the
 * lifecycle's {@code externalId} through the events API, as the person dismissing, with who and
 * when in its metadata.
 *
 * <p><b>Written as the run's identity</b> — the deployer or the registered client — so the event
 * lands only where that identity may write, which deploying checked for the flow's dataset.
 *
 * <p><b>Never lost, never doubled.</b> The run row's {@code failure_event_owed} is the ledger: set
 * in the same statement that ends the run (for a failure, or for a success whose window failed
 * before), cleared once the event is written. What could not be written right away — the api
 * down, Keycloak down — is written by a later sweep, on any instance. Two instances paying the same
 * debt write the same event: its id is derived from what it says, so the copies collapse.
 *
 * <p>A run whose identity Keycloak refused cannot write its own failure event. It stays owed, and
 * is skipped until the flow is deployed afresh with a usable identity; meanwhile the failure is on
 * the run row and the deployment shows <em>needs re-authorisation</em>.
 */
@Slf4j
@Component
public class RunEvents {

    public static final String TYPE = "flow";
    public static final String SUB_TYPE = "run-failed";
    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
    public static final String DISMISSED = "dismissed";

    private static final int MAX_EXTERNAL_ID = 256;
    private static final int MAX_ERROR = 1000;

    private final RunRepository runs;
    private final RunCredentials credentials;
    private final String apiBaseUrl;
    private final Duration requestTimeout;
    private final int perSweep;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    public RunEvents(RunRepository runs, RunCredentials credentials,
                     @Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl,
                     @Value("${datahub.flow.runs.request-timeout:PT30S}") Duration requestTimeout,
                     @Value("${datahub.flow.runs.events-per-sweep:20}") int perSweep) {
        this.runs = runs;
        this.credentials = credentials;
        this.apiBaseUrl = apiBaseUrl;
        this.requestTimeout = requestTimeout;
        this.perSweep = perSweep;
    }

    /**
     * The lifecycle a flow and window's failures share: {@code flow:<flow>:<window start>}, or the
     * run's id for a run without a window. Kept within the events API's 256 characters by
     * shortening a very long flow id to a prefix and a digest of the whole.
     */
    public static String externalId(String flowExternalId, TimeWindow window, UUID runId) {
        String key = window == null ? runId.toString() : window.start().toString();
        String id = "flow:" + flowExternalId + ":" + key;
        if (id.length() <= MAX_EXTERNAL_ID) {
            return id;
        }
        String digest = HexFormat.of().formatHex(sha256(flowExternalId), 0, 8);
        int room = MAX_EXTERNAL_ID - "flow:".length() - 1 - digest.length() - 1 - key.length();
        return "flow:" + flowExternalId.substring(0, room) + "~" + digest + ":" + key;
    }

    /** Writes what the current tenant's runs owe, at most a few per call: the sweep's share. */
    void payOwed(String tenantId) {
        for (StoredRun run : runs.owedEvents(perSweep)) {
            pay(tenantId, run);
        }
    }

    /** Writes what one run owes and clears the debt; leaves it for a later sweep when it cannot. */
    void pay(String tenantId, StoredRun run) {
        if (!run.failureEventOwed() || run.flowId() == null) {
            return;
        }
        DatahubClient api;
        try {
            api = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl(apiBaseUrl)
                    .tokenSupplier(credentials.tokens(tenantId, run.flowId()))
                    .requestTimeout(requestTimeout)
                    .build(), http);
        } catch (RunIdentityException e) {
            log.info("Run {}'s lifecycle event waits for its identity: {}", run.id(), e.getMessage());
            return;
        }
        try {
            switch (run.status()) {
                case "FAILED" -> open(api, run);
                case "SUCCEEDED" -> resolveIfOpen(api, run);
                default -> { }
            }
            runs.eventPaid(run.id());
        } catch (DatahubApiException e) {
            if (permanent(e)) {
                // Retrying cannot help: the dataset is gone, or the identity may not write it. The
                // failure itself stays on the run row.
                log.warn("Run {}'s lifecycle event was refused for good ({}): {}", run.id(),
                        e.statusCode(), e.getMessage());
                runs.eventPaid(run.id());
            } else {
                log.info("Run {}'s lifecycle event could not be written yet ({}); a later sweep "
                        + "tries again", run.id(), e.getMessage());
            }
        } catch (RunIdentityException e) {
            log.info("Run {}'s lifecycle event waits for its identity: {}", run.id(), e.getMessage());
        } catch (RuntimeException e) {
            // One run's event must not stop a sweep's others, nor a run's own finish.
            log.warn("Run {}'s lifecycle event could not be written; a later sweep tries again",
                    run.id(), e);
        }
    }

    private void open(DatahubClient api, StoredRun run) {
        Map<String, String> metadata = metadata(run);
        metadata.put("attempts", String.valueOf(run.attempt()));
        if (run.errorCode() != null) {
            metadata.put("errorCode", run.errorCode());
        }
        if (run.error() != null) {
            metadata.put("error", truncate(run.error()));
        }
        String what = run.window() == null ? "run " + run.id()
                : "the window " + run.window().start() + " to " + run.window().end();
        write(api, run, OPEN, "Flow " + run.flowExternalId() + " failed for " + what
                + (run.errorCode() == null ? "." : ": " + run.errorCode() + "."), metadata);
    }

    /** A success closes its window's lifecycle, but only one whose latest event is still open. */
    private void resolveIfOpen(DatahubClient api, StoredRun run) {
        String externalId = externalId(run.flowExternalId(), run.window(), run.id());
        LatestEventsQuery query = new LatestEventsQuery();
        query.getFilter().setExternalId(List.of(externalId));
        query.getFilter().setType(List.of(TYPE));
        query.getFilter().setSubType(List.of(SUB_TYPE));
        query.setStatus(List.of(OPEN));
        query.setLimit(1);
        List<EventModel> open = api.events().latest(query).getItems();
        if (open == null || open.isEmpty()) {
            return;
        }
        write(api, run, RESOLVED, "Flow " + run.flowExternalId() + " succeeded for the window "
                + run.window().start() + " to " + run.window().end() + ".", metadata(run));
    }

    private void write(DatahubClient api, StoredRun run, String status, String description,
                       Map<String, String> metadata) {
        Instant at = run.finishedAt() != null ? run.finishedAt() : Instant.now();
        EventModel event = new EventModel();
        event.setExternalId(externalId(run.flowExternalId(), run.window(), run.id()));
        event.setType(TYPE);
        event.setSubType(SUB_TYPE);
        event.setStatus(status);
        event.setEventTime(at.toEpochMilli());
        event.setDataSetId(run.dataSetId());
        event.setDescription(description);
        event.setMetadata(metadata);
        event.setId(id(event.getExternalId(), at.toEpochMilli(), status).toString());
        api.events().create(List.of(event));
        log.info("Run {}: {} {} event written for {}", run.id(), status, SUB_TYPE, event.getExternalId());
    }

    private static Map<String, String> metadata(StoredRun run) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (run.window() != null) {
            metadata.put("windowStart", run.window().start().toString());
            metadata.put("windowEnd", run.window().end().toString());
        }
        metadata.put("flow", run.flowExternalId());
        if (run.version() != null) {
            metadata.put("flowVersion", String.valueOf(run.version()));
        }
        metadata.put("runId", run.id().toString());
        return metadata;
    }

    /** 4xx other than 401, 408 and 429: what is wrong will be as wrong next time. */
    private static boolean permanent(DatahubApiException e) {
        int status = e.statusCode();
        return status >= 400 && status < 500 && status != 401 && status != 408 && status != 429;
    }

    /**
     * A UUID v7 on the event's time whose remaining bits hash what the event says, as the events
     * sink does: a write that is retried, or paid by two instances, is the same event.
     */
    static UUID id(String externalId, long eventTimeMillis, String status) {
        byte[] hash = sha256(externalId + "\0" + eventTimeMillis + "\0" + status + "\0" + TYPE
                + "\0" + SUB_TYPE);
        ByteBuffer h = ByteBuffer.wrap(hash);
        long msb = (eventTimeMillis << 16) | 0x7000L | (h.getShort() & 0x0FFFL);
        long lsb = (h.getLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR - 1) + "…";
    }
}
