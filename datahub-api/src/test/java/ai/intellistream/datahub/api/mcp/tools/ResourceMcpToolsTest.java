// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code resource_fetch_nearest} tool: CSV params split correctly, an externalId resolves to
 * the numeric id the service method requires, and the exactly-one-identifier rule holds.
 */
class ResourceMcpToolsTest {

    private final ResourceService resourceService = mock(ResourceService.class);
    private final ResourceMcpTools tools = new ResourceMcpTools(resourceService);

    @Test
    void splitsCsvParamsAndDelegates() {
        when(resourceService.fetchNearestRelatedResources(any(), anyList(), anyInt(), anyList(), anyList()))
                .thenReturn(emptyNetwork());

        tools.fetchNearest(null, 7L, "TIMESERIES, ASSET", 5, "FEEDS,MEASURED_BY", "POLICY");

        verify(resourceService).fetchNearestRelatedResources(
                7L, List.of("TIMESERIES", "ASSET"), 5, List.of("FEEDS", "MEASURED_BY"), List.of("POLICY"));
    }

    @Test
    void resolvesExternalIdToTheNumericIdTheServiceNeeds() {
        Resource resolved = new Resource();
        resolved.setId(42L);
        DataWrapper<NodeModel> found = new DataWrapper<>();
        found.getItems().add(resolved);
        when(resourceService.findAllByIdAndExternalId(Set.of(), Set.of("pump_p101"))).thenReturn(found);
        when(resourceService.fetchNearestRelatedResources(any(), anyList(), anyInt(), anyList(), anyList()))
                .thenReturn(emptyNetwork());

        tools.fetchNearest("pump_p101", null, "TIMESERIES", null, null, null);

        verify(resourceService).fetchNearestRelatedResources(
                eq(42L), eq(List.of("TIMESERIES")), eq(10), eq(List.of()), eq(List.of()));
    }

    @Test
    void refusesAmbiguousOrMissingIdentity() {
        assertThatThrownBy(() -> tools.fetchNearest("x", 1L, "TIMESERIES", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.fetchNearest(null, null, "TIMESERIES", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.fetchNearest(null, 1L, " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end label");
    }

    @Test
    void unknownExternalIdFailsWithAClearMessage() {
        when(resourceService.findAllByIdAndExternalId(Set.of(), Set.of("ghost")))
                .thenReturn(new DataWrapper<>());

        assertThatThrownBy(() -> tools.fetchNearest("ghost", null, "TIMESERIES", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    private static ResourceNetwork emptyNetwork() {
        return new ResourceNetwork(Set.of(), Set.of(), Set.of());
    }
}
