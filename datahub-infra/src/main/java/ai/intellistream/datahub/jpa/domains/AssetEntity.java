// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@DiscriminatorValue("1")
@Getter
@Setter
public class AssetEntity extends NodeEntity {

    /**
     * Geographic location as a raw GeoJSON geometry, stored in a {@code jsonb} column.
     * Kept as an opaque string (see {@code GeoLocation}); migrates to a PostGIS
     * {@code geography} column later without a wire-contract change.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geo_location", columnDefinition = "jsonb")
    private String geoLocation;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssetEntity)) return false;
        return this.getId() != null && this.getId().equals(((AssetEntity) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "AssetEntity{" +
                super.toString() +
                ", geoLocation='" + geoLocation + '\'' +
                '}';
    }
}
