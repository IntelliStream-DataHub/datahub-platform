// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which failures are worth keeping on disk.
 *
 * <p>The case worth pinning is the tenant ceiling. It arrives as a 403, which the spool otherwise
 * treats as a credential someone will fix, so without the body check the buffer fills up with
 * batches the server refuses on every replay, right up to its size or retention limit, and the one
 * message telling the caller their limit can be raised never surfaces.
 */
class IngestResultBufferableTest {

    private static final String TENANT_LIMIT_BODY = """
            {"type":"https://intellistream.ai/errors/tenant-limit-reached",\
            "title":"Tenant limit reached","status":403,\
            "detail":"This tenant has reached its limit of 25000 events. Contact IntelliStream to have it raised.",\
            "metric":"events","limit":25000}""";

    private static IngestResult failedWith(int status, String body) {
        return new IngestResult(0, 10,
                List.of(new IngestResult.BatchError(10, status, "failed", body)));
    }

    @Test
    void networkAndServerBlipsAreBuffered() {
        assertTrue(failedWith(0, null).isBufferable());
        assertTrue(failedWith(429, null).isBufferable());
        assertTrue(failedWith(503, null).isBufferable());
    }

    @Test
    void anExpiredCredentialIsBuffered() {
        // The point of buffering 401/403: data keeps accumulating while a token or a grant is fixed.
        assertTrue(failedWith(401, null).isBufferable());
        assertTrue(failedWith(403, null).isBufferable());
    }

    @Test
    void aBadRequestIsNotBuffered() {
        assertFalse(failedWith(400, null).isBufferable());
        assertFalse(failedWith(413, null).isBufferable());
    }

    @Test
    void aTenantCeilingIsNotBufferedEvenThoughItIsA403() {
        assertFalse(failedWith(403, TENANT_LIMIT_BODY).isBufferable());
    }

    @Test
    void anOrdinaryForbiddenBodyStillBuffers() {
        String accessDenied = """
                {"type":"https://intellistream.ai/errors/dataset-forbidden",\
                "title":"Forbidden","status":403,"detail":"No write access to this data set."}""";
        assertTrue(failedWith(403, accessDenied).isBufferable());
    }

    @Test
    void oneTerminalErrorInABatchStopsTheWholeResultBuffering() {
        IngestResult mixed = new IngestResult(0, 20, List.of(
                new IngestResult.BatchError(10, 503, "blip", null),
                new IngestResult.BatchError(10, 403, "at the ceiling", TENANT_LIMIT_BODY)));
        assertFalse(mixed.isBufferable());
    }

    @Test
    void aSuccessIsNeverBufferable() {
        assertFalse(new IngestResult(10, 0, List.of()).isBufferable());
    }
}
