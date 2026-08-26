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
                labelService, mock(NodeService.class), mock(PolicyEnforcement.class),
                List.of(new AssetUpdateStrategy()));
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
    @DisplayName("a type with no strategy updates through the shared pipeline alone")
    void aTypeWithoutAStrategyStillUpdates() {
        for (NodeEntity node : List.of(new ResourceEntity(), new DatasetEntity())) {
            UpdateResourceForm form = new UpdateResourceForm(1L);
            form.getUpdate().getName().set("Renamed");

            engine().updateNode(node, form);

            assertThat(node.getName()).isEqualTo("Renamed");
        }
    }
}
