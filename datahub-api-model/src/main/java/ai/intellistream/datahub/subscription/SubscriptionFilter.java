// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.json.SingleOrList;
import ai.intellistream.datahub.json.ToStringSerializer;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.datafilters.FilterPatterns;
import ai.intellistream.datahub.models.datafilters.NodeFilter;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filter criteria for {@code POST /subscriptions/filter}.
 *
 * <p>Deliberately not a {@link NodeFilter} subclass: a subscription is not a node. It has no
 * {@code source}, {@code description}, {@code labels} or {@code metadata} column to match on, and
 * inheriting fields no query can honour is the bug {@code NodeFilter}'s own note warns about.
 *
 * <p>What it does share, it shares by name and by meaning, which is what {@code EventFilter} —
 * the other filter that cannot inherit — does too: {@link #id}, {@link #externalId}, {@link #name},
 * {@link #createdTime} and {@link #lastUpdatedTime} behave exactly as they do on a node filter,
 * down to deriving their patterns through {@link FilterPatterns} rather than re-deriving them here.
 * The one criterion with no counterpart in the family is {@link #timeseries}, which is the question
 * this endpoint exists to answer.
 *
 * <p>Every supplied field is combined with AND; within a list field the entries OR together, and an
 * empty list places no restriction. Same rules as the rest of the family.
 */
@Schema(name = "Subscription Query Filter", description = "Subscription Query Filter Object")
@Data
public class SubscriptionFilter {

    /** Subscriptions with any of these ids. Serialised as strings, like every other id on the wire. */
    @Size(max = 1000)
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    @Schema(description = "Subscriptions with any of these ids.", example = "[\"12\", \"18\"]")
    @SingleOrList
    private List<Long> id;

    /**
     * Subscriptions matching any of these external ids. Each entry is either a literal id or a
     * pattern — {@code *} and {@code %} are both wildcards, {@code _} is literal — matched
     * case-insensitively, exactly as {@code NodeFilter.externalId} is. Literal entries resolve
     * through the indexed {@code external_id_hash}; only the patterns need a scan.
     */
    @Size(max = 1000)
    @Schema(description = "Subscriptions matching any of these external ids. `*` and `%` are wildcards; `_` is literal.",
            example = "[\"fleet_dashboard\", \"plant_a_*\"]")
    @SingleOrList
    private List<String> externalId;

    /** Subscriptions whose name matches any entry, on the same pattern rules as {@link #externalId}. */
    @Size(max = 1000)
    @Schema(description = "Subscriptions whose name matches any of these patterns. `*` and `%` are wildcards.",
            example = "[\"Fleet*\", \"Boiler Room Readings\"]")
    @SingleOrList
    private List<String> name;

    /**
     * Subscriptions bound to any of these timeseries. A subscription is bound to several, so this
     * asks "streams at least one of them" — the OR the rest of the list fields use.
     */
    @Schema(
            description = "Return only subscriptions bound to any of these timeseries. Each entry can specify " +
                    "a timeseries id, external id, or both. Empty places no restriction.",
            example = "[{\"id\": 29}, {\"externalId\": \"heater_2012_temp\"}]"
    )
    @Size(max = 1000)
    private Collection<IdCollection> timeseries = new ArrayList<>();

    private TimeFilter createdTime;

    private TimeFilter lastUpdatedTime;

    /** @see NodeFilter#getExternalIdHashes() */
    @JsonIgnore
    public List<Long> getExternalIdHashes() {
        return FilterPatterns.exactExternalIdHashes(this.externalId);
    }

    /** @see NodeFilter#getExternalIdPatterns() */
    @JsonIgnore
    public List<String> getExternalIdPatterns() {
        return FilterPatterns.wildcardPatterns(this.externalId);
    }

    /** @see NodeFilter#getNamePatterns() */
    @JsonIgnore
    public List<String> getNamePatterns() {
        return FilterPatterns.allPatterns(this.name);
    }
}
