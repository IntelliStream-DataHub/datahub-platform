// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;


public interface EdgeRepository extends CrudRepository<EdgeEntity, Long>, EdgeRepo {

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> T findById(long id, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> List<T> findAllByStart(long id, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> List<T> findAllByStartIn(Collection<Long> idList, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> List<T> findAllByEnd(long id, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> List<T> findAllByEndIn(Collection<Long> idList, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> List<T> findAllByStartInOrEndIn(Collection<Long> idStartList, Collection<Long> idEndList, Class<T> type);

    @EntityGraph(attributePaths = { "metadata", "relationshipType" })
    <T> List<T> findAllByIdIn(Collection<Long> id, Class<T> type);

    void deleteByIdIn(List<Long> list);
}

