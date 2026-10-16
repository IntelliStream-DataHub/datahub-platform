// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.graphtransfer.GraphFileCodec;
import ai.intellistream.datahub.api.graphtransfer.GraphTransferLimitException;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An export is never cut short. On a playground tenant it reads past the network limit and refuses a
 * network bigger than the limit, as it refuses any export that is too large. Reading it with the
 * limit applied would have exported only the nearest 5,000 resources of a bigger network, while the
 * export endpoint promises that nothing is exported partially.
 */
class GraphTransferServiceExportCapTest {

    private final ResourceService resourceService = mock(ResourceService.class);
    private final GraphTransferService service = new GraphTransferService(resourceService,
            mock(NodeRepository.class), mock(EdgeRepository.class), mock(PlatformTransactionManager.class), 10);

    private static ResourceNetwork networkOf(int nodes) {
        Set<NodeModel> ns = new HashSet<>();
        for (int i = 1; i <= nodes; i++) {
            Resource r = new Resource();
            r.setId((long) i);
            r.setExternalId("r" + i);
            r.setName("R " + i);
            r.setDataSetId(10L);
            ns.add(r);
        }
        return new ResourceNetwork(ns, new HashSet<>(), new HashSet<>());
    }

    private int limitAskedFor() {
        ArgumentCaptor<RelatedResourcesForm> form = ArgumentCaptor.forClass(RelatedResourcesForm.class);
        verify(resourceService).fetchWholeRelatedResources(form.capture());
        return form.getValue().getLimit();
    }

    @Test
    void aPlaygroundTenantsLargerNetworkIsRefusedNotCutShort() {
        when(resourceService.networkCap()).thenReturn(5_000L);
        when(resourceService.fetchWholeRelatedResources(any())).thenReturn(networkOf(5_001));

        assertThatThrownBy(() -> service.prepareExport(1L))
                .isInstanceOf(GraphTransferLimitException.class)
                .hasMessageContaining("more than 5000 nodes");
        assertThat(limitAskedFor()).isEqualTo(5_001);
    }

    @Test
    void aPlaygroundTenantsNetworkWithinTheCapIsExportedWhole() {
        when(resourceService.networkCap()).thenReturn(5_000L);
        when(resourceService.fetchWholeRelatedResources(any())).thenReturn(networkOf(10));

        assertThat(service.prepareExport(1L).fileName()).isNotBlank();
        assertThat(limitAskedFor()).isEqualTo(5_001);
    }

    @Test
    void anyoneElseKeepsTheExportsOwnLimit() {
        when(resourceService.networkCap()).thenReturn(0L);
        when(resourceService.fetchWholeRelatedResources(any())).thenReturn(networkOf(10));

        service.prepareExport(1L);

        assertThat(limitAskedFor()).isEqualTo(GraphFileCodec.MAX_NODES + 1);
    }
}
