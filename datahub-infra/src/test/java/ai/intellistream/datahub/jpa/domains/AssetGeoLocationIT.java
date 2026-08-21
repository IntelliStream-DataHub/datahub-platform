// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code geo_location} jsonb mapping on {@link AssetEntity} against a real PostgreSQL
 * container: the {@code @JdbcTypeCode(SqlTypes.JSON)} String binding must store the raw GeoJSON into
 * a genuine jsonb column (queryable with {@code ->>}) and read it back, and a null value must persist.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AssetGeoLocationIT.JpaConfig.class)
class AssetGeoLocationIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("asset_geo_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = LabelRepository.class)
    @EntityScan(basePackageClasses = Label.class)
    static class JpaConfig {
    }

    @PersistenceContext
    private EntityManager em;

    private Long persistAsset(String externalId, String geoJson) {
        AssetEntity asset = new AssetEntity();
        asset.setExternalId(externalId);   // derives externalIdHash
        asset.setName("Geo Asset");
        asset.setLabels("ASSET");
        asset.setGeoLocation(geoJson);
        em.persist(asset);
        em.flush();
        Long id = asset.getId();
        em.clear();
        return id;
    }

    @Test
    void geoLocationRoundTripsThroughJsonbColumn() {
        Long id = persistAsset("geo_asset", "{\"type\": \"Point\", \"coordinates\": [10.75, 59.91]}");

        AssetEntity reloaded = em.find(AssetEntity.class, id);
        assertThat(reloaded.getGeoLocation()).contains("\"type\"").contains("Point");

        // Column is genuine jsonb: a nested field is queryable server-side.
        Object type = em.createNativeQuery("SELECT geo_location ->> 'type' FROM node WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(type).isEqualTo("Point");
    }

    @Test
    void nullGeoLocationPersists() {
        Long id = persistAsset("no_geo_asset", null);
        assertThat(em.find(AssetEntity.class, id).getGeoLocation()).isNull();
    }

    /** {@code source} is common to all node types: it must persist on a non-asset ResourceEntity. */
    @Test
    void sourcePersistsOnNonAssetNode() {
        ResourceEntity node = new ResourceEntity();
        node.setExternalId("plain_resource");
        node.setName("Plain Resource");
        node.setLabels("RESOURCE");
        node.setSource("scada");
        em.persist(node);
        em.flush();
        Long id = node.getId();
        em.clear();

        assertThat(em.find(ResourceEntity.class, id).getSource()).isEqualTo("scada");
    }
}
