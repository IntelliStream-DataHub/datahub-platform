// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services.node;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.validation.ResourceFields;
import org.springframework.stereotype.Component;

/**
 * Assets are the only node type with a geographic location — {@code AssetEntity} is the only
 * entity carrying the column — so this is the whole of what an asset update adds to the shared
 * pipeline.
 */
@Component
public class AssetUpdateStrategy implements NodeUpdateStrategy {

    @Override
    public Class<? extends NodeEntity> handles() {
        return AssetEntity.class;
    }

    @Override
    public void apply(NodeEntity node, ResourceFields fields) {
        AssetEntity asset = (AssetEntity) node;
        if (fields.getGeoLocation().getSet() != null) {
            asset.setGeoLocation(fields.getGeoLocation().getSet().getJson());
        }
        if (fields.getGeoLocation().getSetNull()) {
            asset.setGeoLocation(null);
        }
    }
}
