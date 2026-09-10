// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services.node;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.NodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-type step is dispatched by exact entity class. These pin the two ways that goes wrong:
 * a type with no strategy must still update (it just has nothing of its own to add), and a
 * Hibernate proxy must still find its strategy — {@code node.getClass()} on a lazily-loaded entity
 * answers a generated subclass, so the lookup unwraps with {@code Hibernate.getClass}.
 */
class NodeUpdateStrategyRegistryTest {

    private final LabelService labelService = mock(LabelService.class);

    private NodeUpdateService engine() {
        when(labelService.resolveLabelUpdate(any(), any())).thenReturn(Optional.empty());
        return new NodeUpdateService(
                mock(NodeRepository.class), mock(DataSetRepository.class), mock(DataSecurity.class),
                labelService, mock(NodeService.class), mock(PolicyEnforcement.class));
    }

    private static UpdateResourceForm settingGeoLocation() {
        UpdateResourceForm form = new UpdateResourceForm(1L);
        form.getUpdate().getGeoLocation().set(
                new GeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}"));
        return form;
    }

    @Test
    @DisplayName("an asset gets its type-specific field applied")
    void anAssetGetsItsStrategy() {
        AssetEntity asset = new AssetEntity();

        engine().updateNode(asset, settingGeoLocation());

        assertThat(asset.getGeoLocation()).contains("Point");
    }

    /**
     * The proxy case. A lazily-loaded asset is an instance of a generated subclass, so keying the
     * registry on {@code getClass()} would silently skip its strategy and drop the geolocation
     * with no error anywhere.
     */
    @Test
    @DisplayName("a proxied asset still finds its strategy")
    void aProxiedAssetStillDispatches() {
        AssetEntity proxyLike = new AssetEntity() { };   // stands in for the generated subclass
        assertThat(proxyLike.getClass()).isNotEqualTo(AssetEntity.class);

        engine().updateNode(proxyLike, settingGeoLocation());

        assertThat(proxyLike.getGeoLocation()).contains("Point");
    }

    @Test
    @DisplayName("a type registered as NONE updates through the shared pipeline alone")
    void aTypeRegisteredAsNoneStillUpdates() {
        for (NodeEntity node : List.of(new ResourceEntity(), new DatasetEntity())) {
            UpdateResourceForm form = new UpdateResourceForm(1L);
            form.getUpdate().getName().set("Renamed");

            engine().updateNode(node, form);

            assertThat(node.getName()).isEqualTo("Renamed");
        }
    }

    /**
     * The registry is fixed and exhaustive so that a node type nobody has considered fails loudly
     * instead of being quietly half-updated. This is the check that notices: add a seventh entity
     * and it fails here, at the point where the omission is cheap to fix.
     */
    @Test
    @DisplayName("every concrete node entity is registered")
    void theRegistryCoversTheWholeEntityFamily() {
        assertThat(NodeUpdateService.registeredTypes())
                .containsExactlyInAnyOrder(
                        ai.intellistream.datahub.jpa.domains.AssetEntity.class,
                        ai.intellistream.datahub.jpa.domains.ResourceEntity.class,
                        ai.intellistream.datahub.jpa.domains.DatasetEntity.class,
                        ai.intellistream.datahub.jpa.domains.PolicyEntity.class,
                        ai.intellistream.datahub.jpa.domains.FunctionEntity.class,
                        ai.intellistream.datahub.jpa.domains.TimeseriesEntity.class);
    }

    @Test
    @DisplayName("an unregistered node type is refused, not half-applied")
    void anUnregisteredTypeIsRefused() {
        // A NodeEntity that is not one of the six — what a newly added entity looks like before
        // anyone registers it.
        NodeEntity unknown = new NodeEntity() { };
        UpdateResourceForm form = new UpdateResourceForm(1L);
        form.getUpdate().getName().set("Renamed");

        assertThatThrownBy(() -> engine().updateNode(unknown, form))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No update strategy registered");
    }
}
