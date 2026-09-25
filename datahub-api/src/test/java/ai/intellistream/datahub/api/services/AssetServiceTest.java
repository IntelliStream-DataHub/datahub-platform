// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.UpdateAssetForm;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.repositories.node.AssetRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @Mock private AssetRepository assetRepository;
    @Mock private NodeUpdateService nodeUpdateService;
    @Mock private GraphOutbox graphOutbox;
    @InjectMocks private AssetService assetService;

    private static AssetEntity assetEntity(long id, String externalId) {
        AssetEntity e = new AssetEntity();
        e.setId(id);
        e.setExternalId(externalId);
        return e;
    }

    private static GraphDataWrapper<UpdateAssetForm, UpdateRelForm> updating(UpdateAssetForm... forms) {
        var w = new GraphDataWrapper<UpdateAssetForm, UpdateRelForm>();
        w.getNodes().addAll(List.of(forms));
        return w;
    }

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

    /**
     * A body with no {@code filter} block at all still has to answer with assets only.
     *
     * <p>{@code SearchBody.filter} has no default, so the pin had nothing to write to and the query
     * ran across every node type. The limit was then spent on that mixed page and the non-assets
     * were dropped afterwards, so a search for a phrase matching many timeseries returned a handful
     * of assets, or none, while matching ones sat just past the cut.
     */
    @Test
    void searchPinsTheTypeEvenWhenTheBodyHasNoFilter() {
        var body = new SearchBody<ResourceFilter>();
        body.getSearch().setQuery("pump");
        when(resourceService.search(any())).thenReturn(new DataWrapper<>());

        assetService.search(body);

        ArgumentCaptor<SearchBody<ResourceFilter>> sent = ArgumentCaptor.captor();
        verify(resourceService).search(sent.capture());
        assertThat(sent.getValue().getFilter()).isNotNull();
        assertThat(sent.getValue().getFilter().getNodeType()).containsExactly("asset");
    }

    /** Paging is the pipeline's; narrowing the page must not drop its cursor. */
    @Test
    void filterCarriesTheCursorThrough() {
        var retriever = new ResourceRetreiver();
        retriever.setFilter(new ResourceFilter());
        when(resourceService.filter(any())).thenReturn(new DataWrapper<NodeModel>().setNextCursor("abc"));

        assertThat(assetService.filter(retriever).getNextCursor()).isEqualTo("abc");
    }

    /**
     * The pipeline resolves ids as any node, so the request's ids must not reach it: only what the
     * asset repository resolved does. Here the external id names an asset and the id does not.
     */
    @Test
    void deleteForwardsOnlyTheIdsThatResolveToAssets() throws Exception {
        var request = new DataWrapper<IdCollection>();
        IdCollection timeseriesId = new IdCollection();
        timeseriesId.setId(5L);
        IdCollection byExternalId = new IdCollection();
        byExternalId.setExternalId("pump_1");
        request.getItems().addAll(List.of(timeseriesId, byExternalId));
        when(assetRepository.findAllByIdCollection(any())).thenReturn(List.of(assetEntity(7L, "pump_1")));

        assetService.delete(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GraphDataWrapper<Resource, EdgeProxy>> cap =
                ArgumentCaptor.forClass(GraphDataWrapper.class);
        verify(resourceService).delete(cap.capture());
        assertThat(cap.getValue().getNodes()).extracting(Resource::getId).containsExactly(7L);
    }

    @Test
    void deleteOfOnlyNonAssetsDoesNotCallThePipeline() throws Exception {
        var request = new DataWrapper<IdCollection>();
        IdCollection timeseriesId = new IdCollection();
        timeseriesId.setId(5L);
        request.getItems().add(timeseriesId);
        when(assetRepository.findAllByIdCollection(any())).thenReturn(List.of());

        assetService.delete(request);

        verify(resourceService, never()).delete(any());
    }

    /** Pass the loaded entity through authorize, and hand apply back what it was given. */
    @SuppressWarnings("unchecked")
    private void stubSharedStages() {
        when(nodeUpdateService.authorize(any(), any()))
                .thenAnswer(inv -> new NodeUpdateService.Target(inv.getArgument(0), inv.getArgument(1)));
        when(nodeUpdateService.judgeNaming(any())).thenReturn(List.of());
        when(nodeUpdateService.apply(any())).thenAnswer(inv ->
                ((List<NodeUpdateService.Target>) inv.getArgument(0)).stream()
                        .map(NodeUpdateService.Target::entity).toList());
    }

    /**
     * The targets are the entities the asset repository loaded, paired by id or by external id;
     * the external id is matched on its hash, so a differently-cased one still finds its asset.
     */
    @Test
    void updateAppliesTheSharedStagesToTheLoadedAssets() throws Exception {
        UpdateAssetForm byId = new UpdateAssetForm().setId(7L);
        UpdateAssetForm byExternalId = new UpdateAssetForm().setExternalId("Pump_2");
        AssetEntity pump1 = assetEntity(7L, "pump_1");
        AssetEntity pump2 = assetEntity(8L, "pump_2");
        when(assetRepository.findAllByIdOrExternalId(Set.of(7L), Set.of("Pump_2"))).thenReturn(List.of(pump1, pump2));
        stubSharedStages();

        var result = assetService.update(updating(byId, byExternalId));

        ArgumentCaptor<UpdateResourceForm> commands = ArgumentCaptor.captor();
        verify(nodeUpdateService).authorize(commands.capture(), eq(pump1));
        verify(nodeUpdateService).authorize(commands.capture(), eq(pump2));
        assertThat(commands.getAllValues()).extracting(UpdateResourceForm::getId).containsExactly(7L, null);
        assertThat(commands.getAllValues().get(1).getExternalId()).isEqualTo("Pump_2");
        verify(nodeUpdateService).guardRenames(any());
        verify(graphOutbox).queueUpsert(List.of(pump1, pump2), List.of());
        assertThat(result.getNodes()).hasSize(2);
        // No relations: nothing for the generic pipeline to do.
        verify(resourceService, never()).update(any());
    }

    /** The asset's own fields, geoLocation included, reach the shared stages as sent. */
    @Test
    void updateHandsTheAssetFieldsToTheSharedStages() throws Exception {
        UpdateAssetForm form = new UpdateAssetForm().setId(7L);
        form.getUpdate().getName().set("Pump 7");
        form.getUpdate().getGeoLocation().setNull(true);
        when(assetRepository.findAllByIdOrExternalId(Set.of(7L), Set.of()))
                .thenReturn(List.of(assetEntity(7L, "pump_7")));
        stubSharedStages();

        assetService.update(updating(form));

        ArgumentCaptor<UpdateResourceForm> command = ArgumentCaptor.captor();
        verify(nodeUpdateService).authorize(command.capture(), any());
        assertThat(command.getValue().getUpdate().getName()).isSameAs(form.getUpdate().getName());
        assertThat(command.getValue().getUpdate().getGeoLocation()).isSameAs(form.getUpdate().getGeoLocation());
    }

    /** A non-asset target is a 404 like a missing one, and nothing in the batch is written. */
    @Test
    void updateOfANonAssetIs404AndAppliesNothing() {
        var request = updating(new UpdateAssetForm().setId(7L), new UpdateAssetForm().setId(5L));
        when(assetRepository.findAllByIdOrExternalId(Set.of(7L, 5L), Set.of()))
                .thenReturn(List.of(assetEntity(7L, "pump_1")));
        when(nodeUpdateService.authorize(any(), any()))
                .thenAnswer(inv -> new NodeUpdateService.Target(inv.getArgument(0), inv.getArgument(1)));

        assertThatThrownBy(() -> assetService.update(request))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessage("Asset with id: 5 Not found!");
        verify(nodeUpdateService, never()).apply(any());
        verify(graphOutbox, never()).queueUpsert(any(), any());
    }

    /** An id wins over an external id, as it does in the pipeline, so the id is what is looked up. */
    @Test
    void updateChecksTheIdWhenBothAreGiven() {
        var request = updating(new UpdateAssetForm().setId(5L).setExternalId("pump_1"));
        when(assetRepository.findAllByIdOrExternalId(Set.of(5L), Set.of())).thenReturn(List.of());

        assertThatThrownBy(() -> assetService.update(request)).isInstanceOf(ObjectNotFoundException.class);
    }

    /** Relations still go through the generic pipeline, alone: the asset nodes are not updated twice. */
    @Test
    void updateSendsRelationsThroughThePipelineWithoutTheNodes() throws Exception {
        var request = updating(new UpdateAssetForm().setId(7L));
        UpdateRelForm relation = new UpdateRelForm();
        request.getRelations().add(relation);
        when(assetRepository.findAllByIdOrExternalId(Set.of(7L), Set.of()))
                .thenReturn(List.of(assetEntity(7L, "pump_1")));
        stubSharedStages();
        var edgeEcho = new GraphDataWrapper<NodeModel, EdgeProxy>();
        edgeEcho.getRelations().add(new EdgeProxy());
        when(resourceService.update(any())).thenReturn(edgeEcho);

        var result = assetService.update(request);

        ArgumentCaptor<GraphDataWrapper<UpdateResourceForm, UpdateRelForm>> sent = ArgumentCaptor.captor();
        verify(resourceService).update(sent.capture());
        assertThat(sent.getValue().getNodes()).isEmpty();
        assertThat(sent.getValue().getRelations()).containsExactly(relation);
        assertThat(result.getRelations()).isEqualTo(edgeEcho.getRelations());
    }

    /** Relations with no nodes are the pipeline's alone: nothing is loaded or queued here. */
    @Test
    void updateOfOnlyRelationsGoesStraightToThePipeline() throws Exception {
        var request = updating();
        request.getRelations().add(new UpdateRelForm());

        assetService.update(request);

        ArgumentCaptor<GraphDataWrapper<UpdateResourceForm, UpdateRelForm>> sent = ArgumentCaptor.captor();
        verify(resourceService).update(sent.capture());
        assertThat(sent.getValue().getRelations()).isEqualTo(request.getRelations());
        verify(assetRepository, never()).findAllByIdOrExternalId(any(), any());
        verify(graphOutbox, never()).queueUpsert(any(), any());
    }

    @Test
    void deleteOfNothingDoesNotCallThePipeline() throws Exception {
        assetService.delete(new DataWrapper<>());
        verify(resourceService, never()).delete(any());
    }
}
