// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PolicyRepository extends GenericNodeRepository<PolicyEntity> {
    public static final long POLICY = 6;

    // Policies are rendered via PolicyTransformer outside the service transaction, so dataSet and
    // metadata must be loaded up-front to avoid LazyInitializationException. nodeType no longer
    // needs fetching: the transformer stopped reading that association, because a policy's node
    // type is the constant "POLICY". Listing it here would have been a join per lookup for nothing,
    // and — as findAll's missing graph showed — the up-front-fetch approach only holds as long as
    // every finder remembers to repeat it.
    @EntityGraph(attributePaths = { "dataSet", "metadata" })
    @Override
    Optional<PolicyEntity> findById(Long id);
}