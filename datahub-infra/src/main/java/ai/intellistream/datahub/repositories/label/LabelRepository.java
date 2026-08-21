// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.label;

import ai.intellistream.datahub.jpa.domains.Label;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.Set;


public interface LabelRepository extends ListCrudRepository<Label, Long> {

    Label findByHash(Long id);
    Set<Label> findAllByNameIn(Set<String> names);
    List<Label> findAllByNameIn(List<String> names);
    @Query(value = "SELECT L FROM Label L WHERE L.hash IN ?1")
    List<Label> findAllByHashList(Collection<Long> ids);

    // Load labels together with the resource nodes referencing them in a single query, so a
    // delete can decide (and report) which are still in use without an N+1 over each label's nodes.
    @Query("SELECT DISTINCT l FROM Label l LEFT JOIN FETCH l.nodes WHERE l.id IN ?1")
    List<Label> findAllByIdInFetchNodes(Collection<Long> ids);

    @Query("SELECT DISTINCT l FROM Label l LEFT JOIN FETCH l.nodes WHERE l.hash IN ?1")
    List<Label> findAllByHashInFetchNodes(Collection<Long> hashes);

    @Modifying
    @Query("DELETE FROM Label l WHERE l.id IN ?1")
    void deleteAllByIdIn(Collection<Long> ids);
}
