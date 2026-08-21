// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.dto;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpDtoTest {

    private final McpResultConverter converter = new McpResultConverter();

    @Test
    void ofMarksTruncatedWhenCapIsFilled() {
        assertThat(McpList.of(List.of("a", "b"), 5).truncated()).isFalse();
        assertThat(McpList.of(List.of("a", "b", "c"), 3).truncated()).isTrue();
        assertThat(McpList.of(List.of("a", "b"), 5).returned()).isEqualTo(2);
    }

    @Test
    void leanTimeseriesDropsLowSignalFieldsAndEmpties() {
        Timeseries t = new Timeseries();
        t.setId(2291L);
        t.setExternalId("gui_demo_temperature");
        t.setName("GUI demo temperature");
        t.setUnit("degC");
        // no dataSetId, no description, empty metadata -> all must vanish under NON_EMPTY

        String json = converter.convert(LeanTimeseries.from(t), LeanTimeseries.class);

        assertThat(json).contains("\"externalId\":\"gui_demo_temperature\"");
        assertThat(json).contains("\"unit\":\"degC\"");
        assertThat(json).contains("\"id\":\"2291\""); // ToStringSerializer -> string
        assertThat(json).doesNotContain("tableEngine");
        assertThat(json).doesNotContain("valueType");
        assertThat(json).doesNotContain("createdTime");
        assertThat(json).doesNotContain("securityCategories");
        assertThat(json).doesNotContain("metadata");
        assertThat(json).doesNotContain("description");
        assertThat(json).doesNotContain("dataSetId");
    }

    @Test
    void mcpListAlwaysSignalsTruncation() {
        // truncated is a primitive boolean, so it is always present (a cheap, useful "you got
        // everything" signal costing ~17 chars once per response, not per item).
        String notTruncated = converter.convert(McpList.of(List.of("x"), 5), McpList.class);
        assertThat(notTruncated).contains("\"truncated\":false");
        assertThat(notTruncated).contains("\"returned\":1");

        String truncated = converter.convert(McpList.of(List.of("x", "y"), 2), McpList.class);
        assertThat(truncated).contains("\"truncated\":true");
    }
}
