// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.events.EventRetreiver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The arguments {@code event_filter} advertises, and the {@code EventFilter} it builds from them.
 *
 * <p>The tool surface had no test, which is how it came to name its dataset narrowing
 * {@code dataSetIds}/{@code dataSetExternalIds} while the REST contract and every other MCP tool
 * called the same thing {@code dataSetId}. A model queried in one vocabulary and the console read
 * the other, so a chat-offered events view silently lost the dataset it was about.
 */
class EventMcpToolsTest {

    private final EventService eventService = mock(EventService.class);
    private final EventMcpTools tools = new EventMcpTools(eventService);

    private EventRetreiver capturedFilterCall() {
        ArgumentCaptor<EventRetreiver> captor = ArgumentCaptor.forClass(EventRetreiver.class);
        verify(eventService).queryEvents(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void datasetIdsAndExternalIdsBothLandInTheOneFilterField() {
        tools.filterEvents(null, null, null, null,
                List.of(43L, 44L), List.of("data_set_sap"),
                null, null, null, null, null, null);

        assertThat(capturedFilterCall().getFilter().getDataSetId())
                .extracting(IdCollection::getId, IdCollection::getExternalId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(43L, null),
                        org.assertj.core.groups.Tuple.tuple(44L, null),
                        org.assertj.core.groups.Tuple.tuple(null, "data_set_sap"));
    }

    @Test
    void relatedResourcesTakeIdsAsWellAsExternalIds() {
        // Narrowing by resource id was not expressible before: the tool only took externalIds.
        tools.filterEvents(null, null, null, null, null, null,
                List.of(99L), List.of("pump_21"),
                null, null, null, null);

        assertThat(capturedFilterCall().getFilter().getRelatedResources())
                .extracting(IdCollection::getId, IdCollection::getExternalId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(99L, null),
                        org.assertj.core.groups.Tuple.tuple(null, "pump_21"));
    }

    @Test
    void anEmptyOrBlankListMeansNoNarrowingRatherThanNarrowingToNothing() {
        // A model padding an array with an empty string means "no dataset in mind". The REST surface
        // reads an explicit [] the other way — as "match no dataset" — because a client hand-writing
        // it means exactly that.
        tools.filterEvents(null, null, null, null,
                List.of(), List.of("  "),
                null, null, null, null, null, null);

        assertThat(capturedFilterCall().getFilter().getDataSetId()).isNullOrEmpty();
    }

    @Test
    void theSingularFieldsMirrorTheRestContract() {
        tools.filterEvents("Alarm", "threshold", "SAP", "work_order_*",
                null, null, null, null,
                "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z", null, 50);

        var filter = capturedFilterCall().getFilter();
        assertThat(filter.getType()).containsExactly("Alarm");
        assertThat(filter.getSubType()).containsExactly("threshold");
        assertThat(filter.getSource()).containsExactly("SAP");
        assertThat(filter.getExternalId()).containsExactly("work_order_*");
        assertThat(filter.getEventTime().getMin()).isNotNull();
        assertThat(filter.getEventTime().getMax()).isNotNull();
    }

    @Test
    void groupByIsPassedThroughRatherThanFolded() {
        tools.filterEvents(null, null, null, null, null, null, null, null,
                null, null, "dataSetId", null);

        verify(eventService).queryEvents(any(), eq("dataSetId"));
    }
}
