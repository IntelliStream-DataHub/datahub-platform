// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One agent definition. See {@code V43__agents.sql} for what each column is for and why the
 * prompt and the tools live here while the credential lives in Vault.
 *
 * <p>The nullable boxed types are load-bearing: {@code null} means "not stated for this agent",
 * and the deployment-wide default applies. The same idiom {@code TenantFeatures} uses for its
 * flags, and for the same reason — a primitive cannot distinguish unset from a real value, and
 * "unset" is the common case.
 */
@Entity
@Table(name = "agent")
@Getter
@Setter
@NoArgsConstructor
public class AgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "instructions")
    private String instructions;

    /**
     * The explicit tool list, mapped straight onto Postgres' {@code text[]} rather than a join
     * table: it is a short, unordered, wholly-replaced set of names read on every turn, so a
     * child table would buy a join and an ordering question for nothing.
     *
     * <p>Never null — an empty list is an agent with no tools, which is a meaningful state and
     * must not read as "all of them".
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tool_allowlist", columnDefinition = "text[]", nullable = false)
    private List<String> toolAllowlist = new ArrayList<>();

    /** Wire name of a {@code ChatEffort} — where the picker starts, not what it must stay at. */
    @Column(name = "default_effort")
    private String defaultEffort;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "max_iterations")
    private Integer maxIterations;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Left to the Postgres default so native inserts and Hibernate agree. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
