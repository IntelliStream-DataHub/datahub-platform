// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Encapsulates find-or-create for {@link RelationshipType} with a concurrency shield.
 *
 * <p>The inline version used to live in {@code ResourceService.updateEdge/mapEdge}:
 * {@code findByHash → if null → save}. Two concurrent edge creates naming the same
 * relationship could both see {@code null}, both issue an INSERT, and the loser would hit
 * the {@code relationship_hash_key} UNIQUE constraint — failing the whole outer request.
 * This service runs the check-then-act in a nested {@code REQUIRES_NEW} transaction and
 * retries on {@link DataIntegrityViolationException}: the loser's retry sees the winner's
 * row and returns it. Mirrors the pattern in {@code LabelService.findAllAndCreateFromNames}.
 */
@Service
@Slf4j
public class RelationshipTypeService {

    private static final int MAX_RACE_RETRIES = 3;

    private final RelationshipTypeRepository repository;
    private final TransactionTemplate requiresNewTx;

    public RelationshipTypeService(RelationshipTypeRepository repository,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Return the existing {@link RelationshipType} for the given name, or create one if
     * none exists. Name normalization (uppercase + xx3 hash) matches
     * {@link RelationshipType#setName(String)} so lookups stay consistent regardless of the
     * caller's casing.
     */
    public RelationshipType findOrCreateByName(String name) {
        if (name == null || name.chars().noneMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("Relationship type name must not be blank");
        }
        final String normalized = name.toUpperCase();
        final long hash = LongHashFunction.xx3().hashChars(normalized);

        int attempts = 0;
        while (true) {
            try {
                return requiresNewTx.execute(status -> {
                    RelationshipType existing = repository.findByHash(hash);
                    if (existing != null) return existing;
                    RelationshipType rt = new RelationshipType();
                    rt.setName(name);
                    // saveAndFlush forces the INSERT (and any UNIQUE violation) inside the
                    // REQUIRES_NEW tx, so we can catch and retry here instead of letting the
                    // failure escape at the outer tx commit.
                    return repository.saveAndFlush(rt);
                });
            } catch (DataIntegrityViolationException e) {
                attempts++;
                if (attempts >= MAX_RACE_RETRIES) {
                    log.error("RelationshipType creation exhausted {} retries for {}", MAX_RACE_RETRIES, name);
                    throw e;
                }
                log.warn("Concurrent RelationshipType creation race for {}; retry {}/{}",
                        name, attempts, MAX_RACE_RETRIES);
            }
        }
    }
}
