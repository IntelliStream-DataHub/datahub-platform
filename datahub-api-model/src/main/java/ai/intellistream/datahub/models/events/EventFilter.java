// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.json.SingleOrList;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.datafilters.FilterPatterns;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Filter criteria for {@code POST /events/filter}.
 *
 * <p><b>Deliberately not a {@link ai.intellistream.datahub.models.datafilters.NodeFilter}.</b>
 * Events are not nodes: they live in ClickHouse rather than the {@code node} table, their id is a
 * UUID string where a node's is a long, and the {@code events} table has no {@code name} column at
 * all — only {@code description}. Extending the node base would inherit {@code id} and
 * {@code name} that no reader could honour, which is the failure this whole refactor exists to
 * remove.
 *
 * <p>What it does instead is match the base <em>field for field</em> wherever ClickHouse can back
 * it: {@code externalId}, {@code source}, {@code metadata}, {@code createdTime},
 * {@code lastUpdatedTime} and {@code dataSetId} all carry the same names, types and semantics they
 * have on {@code NodeFilter} — including the singular naming of the {@link SingleOrList} fields.
 * {@code FilterContractParityTest} pins that, since the compiler cannot.
 *
 * <p>The {@code id} field this filter used to declare is gone. It was typed {@code Long} while
 * {@code EventModel.id} is a {@code String} UUID, and nothing ever read it — filtering by it
 * silently did nothing.
 */
@Schema(name = "EventFilter", description = "Configure event filter.")
@EqualsAndHashCode(callSuper = false)
@Data
@Slf4j
public class EventFilter {

    /**
     * Events matching any of these external ids — literal or wildcard, in the same shape as
     * {@code NodeFilter.externalId}. {@code *} and {@code %} are wildcards, {@code _} is literal.
     * Literal entries resolve through the indexed {@code external_id_hash}; only patterns scan.
     *
     * <p><b>A literal entry matches case-sensitively</b>, unlike the node filters. Every event
     * writer hashes the external id verbatim, so the stored key for {@code shift_report_1} is not
     * the key for {@code SHIFT_REPORT_1}; nodes lowercase before hashing and so match either.
     * A wildcard entry goes through ILIKE instead and is case-insensitive, which makes
     * {@code "SHIFT_REPORT_1*"} a case-insensitive way to ask the same question.
     *
     * <p>Replaced {@code externalIdPrefix}, which could only be given once and only matched a
     * prefix. {@code "work_order_*"} says the same thing.
     */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Events matching any of these external ids. `*` and `%` are wildcards; `_` is literal.",
            example = "[\"work_order_1234\", \"work_order_*\"]")
    private List<String> externalId;

    /** The sources the event may have come from. A pattern list, like {@link #externalId}. */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Events whose source matches any of these patterns. `*` and `%` are wildcards.",
            example = "[\"SAP\", \"opc_*\"]")
    private List<String> source;

    /**
     * Events of any of these types. A pattern list on the same rules as {@link #externalId}, so
     * {@code ["alarm", "warning"]} asks for either and {@code ["maint_*"]} for a family of them.
     *
     * <p>These were single exact-match strings while the rest of the filter took lists, which made
     * the most-used event criteria the least capable ones: "alarms and warnings" needed two calls,
     * even though {@code event_filter}'s own {@code groupBy=type} hands you several at once.
     */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Events matching any of these types. `*` and `%` are wildcards.",
            example = "[\"Alarm\", \"Warning\"]")
    private List<String> type;

    /** Events matching any of these sub-types. A pattern list, like {@link #type}. */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Events matching any of these sub-types. `*` and `%` are wildcards.",
            example = "[\"Electrical\"]")
    private List<String> subType;

    /** Events in any of these statuses. A pattern list, like {@link #type}. */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Events matching any of these statuses. `*` and `%` are wildcards.",
            example = "[\"OPEN\", \"IN_PROGRESS\"]")
    private List<String> status;

    @Schema(description = """
            Restrict to events in these data sets, each given by id or externalId — the same \
            reference shape as relatedResources below. A data set stands in for everything beneath \
            it in the BELONGS_TO hierarchy, so naming a parent covers its children; an externalId \
            naming no data set contributes nothing. Omit the field (or send null) for no data set \
            restriction; an explicit empty list matches nothing.""",
            example = "[{\"id\": \"43\"}, {\"externalId\": \"data_set_sap\"}]")
    // Null must stay null, and this field must NOT get a default empty list the way
    // metadata/relatedResources below do: here the two mean opposite things — null is "no data set
    // restriction", [] is "narrow to no data sets". datahub-api coerces null collections to empty
    // for every other field, so without this a null would silently match no events at all.
    @SingleOrList
    @JsonSetter(nulls = Nulls.SET)
    private List<IdCollection> dataSetId;

    @Schema(description = "Metadata entries that must all be present. A null value matches the key alone.",
            example = "{\"health\": \"good\", \"size\": null}")
    private Map<String, String> metadata = new HashMap<>();

    // Plural, unlike externalId/source/type/subType/status above: these entries AND together, so a
    // second one narrows the query where a second type would widen it. Same reason NodeFilter.labels
    // keeps its plural.
    @SingleOrList
    @Schema(description = "The event must be related to ALL of these resources. Each entry may carry an id, an externalId, or both.", example = "[{\"id\": 22, \"externalId\": \"work_order_sap_1234\"}]")
    private Collection<IdCollection> relatedResources = new ArrayList<>();

    private TimeFilter createdTime;

    private TimeFilter lastUpdatedTime;

    private TimeFilter eventTime;

    /**
     * The literal entries of {@link #externalId}, <em>unhashed</em>.
     *
     * <p>Deliberately not hashed here, unlike {@code NodeFilter.getExternalIdHashes()}. An event's
     * {@code external_id_hash} is BLAKE3 over {@code externalId + tenantId} — 128 bits, salted per
     * tenant — which is what every event write, update and delete computes. A wire DTO has no
     * tenant, so it cannot produce that value, and producing the node hash instead would silently
     * match nothing: different algorithm, different width, no salt.
     */
    @JsonIgnore
    public List<String> getExactExternalIds(){
        return FilterPatterns.literals(this.externalId);
    }

    /** The wildcard entries of {@link #externalId}, as SQL LIKE patterns. */
    @JsonIgnore
    public List<String> getExternalIdPatterns(){
        return FilterPatterns.wildcardPatterns(this.externalId);
    }

    /** {@link #source} as SQL LIKE patterns; a literal source yields a pattern matching it exactly. */
    @JsonIgnore
    public List<String> getSourcePatterns(){
        return FilterPatterns.allPatterns(this.source);
    }
}
