// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssetService} is the typed asset family every other node type already had, and it is a
 * thin adapter: each call is the shared {@link ResourceService} pipeline with the {@code ASSET}
 * discriminator pinned. These pin the delegation and the two places pinning could go wrong — a
 * caller-supplied {@code nodeType} widening the query, and a non-asset id answering as an asset.
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock private ResourceService resourceService;
    @InjectMocks private AssetService assetService;

    private static DataWrapper<NodeModel> wrapping(NodeModel... nodes) {
        var w = new DataWrapper<NodeModel>();
        w.getItems().addAll(List.of(nodes));
        return w;
    }

    @Test
    void createHandsTheBodiesToThePipelineUntouched() throws Exception {
        Asset asset = new Asset();
        asset.setExternalId("pump_1");
        asset.setName("Pump 1");
        var request = new DataWrapper<Asset>();
        request.getItems().add(asset);

        var echo = new GraphDataWrapper<NodeModel, EdgeProxy>();
        Asset createdEcho = new Asset();
        createdEcho.setId(5L);
        echo.getNodes().add(createdEcho);
        when(resourceService.create(any())).thenReturn(echo);

        DataWrapper<Asset> result = assetService.create(request);

        assertThat(result.getItems()).singleElement().isSameAs(createdEcho);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GraphDataWrapper<NodeModel, RelForm>> cap =
                ArgumentCaptor.forClass(GraphDataWrapper.class);
        verify(resourceService).create(cap.capture());
        assertThat(cap.getValue().getNodes()).singleElement().isSameAs(asset);
        // The DTO seeds its own type-label, which is what routes it.
        assertThat(asset.getLabels()).contains("ASSET");
    }

    @Test
    void getReturnsTheAsset() {
        Asset asset = new Asset();
        asset.setId(5L);
        when(resourceService.get(5L)).thenReturn(wrapping(asset));

        assertThat(assetService.get(5L).getItems()).singleElement().isSameAs(asset);
    }

    /** An id that resolves to some other node type is not an asset, and must not be reported as one. */
    @Test
    void getReportsANonAssetAsMissing() {
        when(resourceService.get(5L)).thenReturn(wrapping(new Timeseries()));

        assertThatThrownBy(() -> assetService.get(5L)).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void byIdsKeepsOnlyAssets() {
        Asset asset = new Asset();
        when(resourceService.findAllByIdAndExternalId(any(), any()))
                .thenReturn(wrapping(asset, new Timeseries(), new Resource()));

        assertThat(assetService.byIds(Set.of(1L), Set.of()).getItems()).containsExactly(asset);
    }

    /**
     * nodeType entries OR together, so a caller-supplied one had to be replaced, not merged —
     * otherwise a request to /assets carrying {@code "nodeType": ["timeseries"]} would come back
     * with timeseries in it.
     */
    @Test
    void filterReplacesACallerSuppliedNodeType() {
        var retriever = new ResourceRetreiver();
        var filter = new ResourceFilter();
        filter.setNodeType(List.of("timeseries"));
        retriever.setFilter(filter);
        when(resourceService.filter(any())).thenReturn(new DataWrapper<>());

        assetService.filter(retriever);

        assertThat(filter.getNodeType()).containsExactly("asset");
    }

    @Test
    void searchReplacesACallerSuppliedNodeType() {
        var body = new SearchBody<ResourceFilter>();
        var filter = new ResourceFilter();
        filter.setNodeType(List.of("policy", "dataset"));
        body.setFilter(filter);
        when(resourceService.search(any())).thenReturn(new DataWrapper<>());

        assetService.search(body);

        assertThat(filter.getNodeType()).containsExactly("asset");
    }

    /** Paging is the pipeline's; narrowing the page must not drop its cursor. */
    @Test
    void filterCarriesTheCursorThrough() {
        var retriever = new ResourceRetreiver();
        retriever.setFilter(new ResourceFilter());
        when(resourceService.filter(any())).thenReturn(new DataWrapper<NodeModel>().setNextCursor("abc"));

        assertThat(assetService.filter(retriever).getNextCursor()).isEqualTo("abc");
    }

    @Test
    void deleteForwardsIdsAndExternalIdsToThePipeline() throws Exception {
        var request = new DataWrapper<IdCollection>();
        IdCollection byId = new IdCollection();
        byId.setId(5L);
        IdCollection byExternalId = new IdCollection();
        byExternalId.setExternalId("pump_1");
        request.getItems().addAll(List.of(byId, byExternalId));

        assetService.delete(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GraphDataWrapper<Resource, EdgeProxy>> cap =
                ArgumentCaptor.forClass(GraphDataWrapper.class);
        verify(resourceService).delete(cap.capture());
        assertThat(cap.getValue().getNodes()).hasSize(2);
    }

    @Test
    void deleteOfNothingDoesNotCallThePipeline() throws Exception {
        assetService.delete(new DataWrapper<>());
        verify(resourceService, org.mockito.Mockito.never()).delete(any());
    }
}
