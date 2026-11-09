// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.records.RecordsService.Lookup;
import ai.intellistream.datahub.flow.runs.RunService;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A value no flow wrote is an answer, not a missing resource: the lookup says so with a 200. */
class RecordsLookupTest {

    @Test
    void aSeriesNoFlowWroteIsAnEmptyAnswer() {
        Client clickhouse = mock(Client.class);
        when(clickhouse.queryAll(anyString(), anyMap())).thenReturn(List.of());
        RunService runs = mock(RunService.class);
        FlowService flows = mock(FlowService.class);
        when(flows.requireRead()).thenReturn(new Caller("user-1", false, true, false, DataSets.ALL, DataSets.ALL));
        JsonMapper json = JsonMapper.builder().build();

        Lookup answer = new RecordsService(runs, flows, () -> clickhouse, json, Duration.ofSeconds(1))
                .lookup("ts:untouched", Instant.parse("2026-10-21T10:00:00Z"));

        assertThat(answer.run()).isNull();
        assertThat(json.writeValueAsString(answer)).isEqualTo("{}");
    }
}
