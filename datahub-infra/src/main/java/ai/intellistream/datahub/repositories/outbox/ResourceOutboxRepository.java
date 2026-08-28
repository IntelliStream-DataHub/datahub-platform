// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.outbox;

import ai.intellistream.datahub.jpa.domains.ResourceOutboxEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ResourceOutboxRepository extends JpaRepository<ResourceOutboxEntity, Long> {

    /**
     * The key every drainer locks on. The advisory-lock space is per <em>database</em> and each
     * tenant is its own database, so one constant gives one drainer per tenant across the whole
     * fleet — no tenant needs to be mixed into the key.
     *
     * <p>Arbitrary but fixed: changing it would let an old and a new instance drain the same
     * tenant concurrently during a rolling deploy. Anything else in this codebase taking an
     * advisory lock must pick a different constant and say so here.
     */
    long DRAIN_LOCK_KEY = 0x6F7574626F78L; // "outbox" in ASCII

    /**
     * Tries to take the per-tenant drain lock for the duration of the <em>current transaction</em>,
     * returning false immediately if another drainer holds it.
     *
     * <p>Transaction-scoped ({@code _xact_}) rather than session-scoped, which matters twice over:
     * pgbouncer pools by transaction and does not pin a session to a backend, so a session lock
     * would be taken on one connection and looked for on another; and a transaction lock cannot
     * leak — if the instance holding it dies mid-drain, Postgres releases it with the transaction.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryDrainLock(@Param("key") long key);

    List<ResourceOutboxEntity> findByAppliedAtIsNullOrderByIdAsc(Limit limit);

    /** Cheap "is there anything to do?" probe for the sweep; answered from the partial index. */
    boolean existsByAppliedAtIsNull();

    @Modifying
    @Query("UPDATE ResourceOutboxEntity o SET o.appliedAt = :appliedAt WHERE o.id IN :ids")
    void markApplied(@Param("ids") Collection<Long> ids, @Param("appliedAt") Instant appliedAt);

    @Modifying
    @Query("UPDATE ResourceOutboxEntity o SET o.attempts = :attempts, o.nextAttemptAt = :nextAttemptAt, "
            + "o.lastError = :lastError WHERE o.id = :id")
    void recordFailure(@Param("id") Long id,
                       @Param("attempts") int attempts,
                       @Param("nextAttemptAt") Instant nextAttemptAt,
                       @Param("lastError") String lastError);

    /** Carries its own transaction: unlike the others, the purge is not called from inside a drain. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ResourceOutboxEntity o WHERE o.appliedAt IS NOT NULL AND o.appliedAt < :cutoff")
    int deleteAppliedBefore(@Param("cutoff") Instant cutoff);
}
