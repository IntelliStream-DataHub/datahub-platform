// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One queued graph-sync command, written inside the same transaction as the node or edge change
 * it describes. See {@code V42__resource_outbox.sql} for why the queue exists and what each
 * column is for.
 *
 * <p>The payload is held as an opaque {@code String} rather than a mapped object graph — the same
 * choice {@link AssetEntity#getGeoLocation()} makes. The applier is the only thing that reads it,
 * it parses the JSON itself, and keeping the column opaque means a payload-shape change is a
 * code change rather than a schema migration.
 */
@Entity
@Table(name = "resource_outbox")
@Getter
@Setter
@NoArgsConstructor
public class ResourceOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Insertable but left null by the writer so Postgres' {@code now()} default applies: a row is
     * due the moment it commits. Only a failed apply pushes it into the future.
     */
    @Column(name = "next_attempt_at", insertable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    public ResourceOutboxEntity(String payload) {
        this.payload = payload;
    }
}
