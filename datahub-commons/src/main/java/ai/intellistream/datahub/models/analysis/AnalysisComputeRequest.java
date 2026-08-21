// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.analysis;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The api→compute contract, built entirely from existing wire DTOs: the already-authorized,
 * already-bucketed focus + candidate {@link DataCollection}s (the exact shape
 * {@code retrieveDatapointsFromCH} returns), the {@link EdgeProxy} edges among them, and the
 * original {@link AnalysisForm} (which carries every analysis parameter and the focus id).
 */
@Data
public class AnalysisComputeRequest {

    /** The original request form — the source of truth for all parameters and the focus id. */
    private AnalysisForm form;

    /** Bucket width the api derived from the window (also what it aggregated ClickHouse to). */
    private int bucketSeconds;

    private DataCollection<DatapointAggsDTO> focus;
    private List<DataCollection<DatapointAggsDTO>> candidates = new ArrayList<>();

    /** Graph edges among focus + candidates, for the relationship path. */
    private List<EdgeProxy> edges = new ArrayList<>();
}
