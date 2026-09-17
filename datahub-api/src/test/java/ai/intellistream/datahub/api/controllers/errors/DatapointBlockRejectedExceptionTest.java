// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.binary.FrameFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DatapointBlockRejectedExceptionTest {

    /** Every refusal the binary path can raise, one per reason. */
    private static List<DatapointBlockRejectedException> everyRefusal() {
        List<DatapointBlockRejectedException> all = new ArrayList<>();
        for (FrameFormatException.Reason reason : FrameFormatException.Reason.values()) {
            all.add(DatapointBlockRejectedException.from(new FrameFormatException(reason, 0, "x")));
        }
        all.add(DatapointBlockRejectedException.unknownTimeseries(List.of(1L)));
        all.add(DatapointBlockRejectedException.valueTypeMismatch(0, List.of(1L)));
        all.add(DatapointBlockRejectedException.externalIdMismatch(0, List.of(1L)));
        all.add(DatapointBlockRejectedException.tooManyInFlight(1));
        all.add(DatapointBlockRejectedException.unsupportedEncoding("gzip"));
        return all;
    }

    /**
     * RFC 9457 §4: a problem type documents the status it is used with. One type answering 400 or
     * 413 or 422 by its reason leaves a client that branches on type to read the reason instead.
     */
    @Test
    @DisplayName("each problem type answers with one status and one title")
    void oneTypeOneStatus() {
        Map<URI, Set<HttpStatus>> statuses = new HashMap<>();
        Map<URI, Set<String>> titles = new HashMap<>();
        for (DatapointBlockRejectedException refusal : everyRefusal()) {
            statuses.computeIfAbsent(refusal.getType(), t -> new HashSet<>()).add(refusal.getStatus());
            titles.computeIfAbsent(refusal.getType(), t -> new HashSet<>()).add(refusal.getTitle());
        }
        assertThat(statuses).allSatisfy((type, seen) -> assertThat(seen).as("statuses for %s", type).hasSize(1));
        assertThat(titles).allSatisfy((type, seen) -> assertThat(seen).as("titles for %s", type).hasSize(1));
    }

    @Test
    @DisplayName("every type is one Problems declares, so it is documented and gets a retry verdict")
    void typesComeFromProblems() {
        assertThat(everyRefusal()).extracting(DatapointBlockRejectedException::getType)
                .allSatisfy(type -> assertThat(type.toString()).startsWith(Problems.BASE))
                .containsOnly(Problems.INVALID_FRAME, Problems.type("request-too-large"), Problems.UNKNOWN_TIMESERIES,
                        Problems.VALUE_TYPE_MISMATCH, Problems.EXTERNAL_ID_MISMATCH, Problems.TOO_MANY_IN_FLIGHT,
                        Problems.UNSUPPORTED_MEDIA_TYPE);
    }

    /** The Java and Rust SDKs find a stale series cache by these strings in the body; keep them there. */
    @Test
    @DisplayName("the reason survives the split, so the SDKs' stale-series match still holds")
    void reasonsTheSdksMatchAreKept() {
        assertThat(DatapointBlockRejectedException.unknownTimeseries(List.of(1L)).getReason())
                .isEqualTo("unknown-timeseries");
        assertThat(DatapointBlockRejectedException.externalIdMismatch(0, List.of(1L)).getReason())
                .isEqualTo("external-id-mismatch");
    }
}
