// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DataSetRepository extends GenericNodeRepository<DatasetEntity>, DataSetCustomRepo {

    @Override
    @EntityGraph(attributePaths = {"metadata"})
    List<DatasetEntity> findAll();
    /**
     * Every dataset reachable downward from {@code rootIds} through {@code BELONGS_TO}, including
     * the roots themselves. This is the dataset-ACL closure: a grant on a root covers everything
     * beneath it, which is what keeps the number of Keycloak groups proportional to access domains
     * rather than to dataset count.
     *
     * <h2>Edge direction</h2>
     * {@code DataSetModel.connectedDataSets} is "the dataset this dataset is part of", and
     * {@code DataSetTransformer.toGraphForm} writes that edge as {@code from = parent},
     * {@code to = child}. So the stored row is {@code rel_start = parent, rel_end = child} and
     * descending means following {@code rel_start -> rel_end}. The relationship is *named*
     * {@code BELONGS_TO}, which reads in the opposite direction to how it is stored — do not
     * "fix" the traversal to match the name.
     *
     * <h2>Cycles</h2>
     * Nothing stops {@code connectedDataSets} forming a cycle, so this uses {@code UNION} rather
     * than {@code UNION ALL}: duplicate ids are discarded and the recursion terminates once a
     * round adds nothing new. A diamond (two paths to the same dataset) is likewise collapsed.
     *
     * <p>Restricted to dataset nodes at both ends so that ordinary resource relationships, which
     * share the {@code edge} table, can never widen an ACL.
     *
     * @param datasetType always {@code NodeType.DATASET}; passed rather than inlined so the
     *                    discriminator has one source of truth
     */
    @Query(value = """
    WITH RECURSIVE descendants(id) AS (
        SELECT n.id
        FROM node n
        WHERE n.id IN (:rootIds)
          AND n.node_type = :datasetType
      UNION
        SELECT child.id
        FROM descendants d
        JOIN edge e
          ON e.rel_start = d.id
        JOIN relationship_type rt
          ON rt.id = e.relationship_type_id
         AND rt.name = :relationshipType
        JOIN node child
          ON child.id = e.rel_end
         AND child.node_type = :datasetType
    )
    SELECT id FROM descendants
    """, nativeQuery = true)
    List<Long> findDatasetClosure(@Param("rootIds") Collection<Long> rootIds,
                                  @Param("datasetType") long datasetType,
                                  @Param("relationshipType") String relationshipType);

    /**
     * Resolve dataset external ids to node ids. Uses the indexed {@code external_id_hash} rather
     * than the text column, matching how the rest of the codebase looks nodes up.
     */
    @Query(value = """
    SELECT n.id
    FROM node n
    WHERE n.external_id_hash IN (:externalIdHashes)
      AND n.node_type = :datasetType
    """, nativeQuery = true)
    List<Long> findDatasetIdsByExternalIdHashIn(
            @Param("externalIdHashes") Collection<Long> externalIdHashes,
            @Param("datasetType") long datasetType);

}