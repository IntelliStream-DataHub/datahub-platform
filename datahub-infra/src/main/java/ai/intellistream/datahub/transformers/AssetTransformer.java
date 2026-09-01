// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.GeoLocation;

/**
 * {@code AssetEntity} to {@link Asset}. An asset is a node that can be a navigation root and is
 * the only kind carrying a geographic location, which is the whole of what it adds to the shared
 * node shape.
 */
public final class AssetTransformer {

    private AssetTransformer() {
    }

    public static Asset from(AssetEntity entity) {
        Asset dto = NodeBaseFields.apply(new Asset(), entity);
        dto.setIsRoot(entity.getIsRoot());
        if (entity.getGeoLocation() != null) {
            dto.setGeoLocation(new GeoLocation(entity.getGeoLocation()));
        }
        return dto;
    }
}
