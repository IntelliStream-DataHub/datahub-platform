// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.helpers.text.Labels;
import ai.intellistream.datahub.helpers.text.WildcardPatterns;
import ai.intellistream.datahub.json.SingleOrList;
import ai.intellistream.datahub.json.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The criteria every node type can be filtered by — the query counterpart of
 * {@code NodeModel}, which is the wire base its subjects ({@code DataSetModel}, {@code Resource},
 * {@code Timeseries}) already share.
 *
 * <p>That correspondence is the rule for what belongs here: a field earns a place in this class
 * when the column it matches exists on {@code NodeEntity} for <em>every</em> node type, so every
 * subclass can honour it. It is deliberately not "whatever the filters happened to have in common"
 * — the class this one replaces, {@code DataFilter}, carried four min/max timestamp fields that no
 * query ever read, and only two of the four filters extended it. A field declared here without a
 * reader in {@code NodePredicateBuilder} is the same bug.
 *
 * <p>Every supplied field is combined with AND. Within a list field the entries OR together, so
 * {@code name: ["Pump%", "Valve%"]} means "named like either" while
 * {@code name: [...], source: "sap"} means "named like either AND from sap". The two exceptions
 * are {@link #metadata} and {@link #labels}, where every entry must be present — see their notes.
 *
 * <p><b>The list fields are named in the singular even though they take lists.</b> Every one of
 * them carries {@link SingleOrList}, so {@code name: "Pump 1"} and {@code name: ["Pump 1", "Pump 2"]}
 * are both accepted; the singular name is the one that reads correctly in the common case of asking
 * for a single thing, and the plural spelling made the scalar form look like a mistake. The
 * exceptions are {@link #labels} and {@code EventFilter.relatedResources}, which stay plural because
 * their entries AND together rather than OR — the plural is the signal that supplying more than one
 * narrows the query instead of widening it.
 *
 * <p><b>An empty list places no restriction.</b> It does not mean "match nothing": an empty
 * {@code IN} is not valid SQL, and a caller who built a list and found nothing to put in it means
 * the former far more often than the latter. Null means the same. The one field where empty and
 * null diverge is {@code dataSetId} on {@link DataSetScopedFilter}, for the reason stated there.
 */
@Data
public abstract class NodeFilter {

    /**
     * Nodes with any of these ids. Serialised as strings, like every other id on the wire, so a
     * JavaScript client cannot round them through a double and lose precision.
     */
    @Size(max = 1000)
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    @Schema(description = "Nodes with any of these ids.", example = "[\"12\", \"18\"]")
    @SingleOrList
    private List<Long> id;

    /**
     * Nodes matching any of these external ids. Each entry is either a literal id or a pattern —
     * {@code *} and {@code %} are both wildcards — so one list covers exact lookup, prefix search
     * and contains search at once: {@code ["sap_work_orders", "plant_*", "*_archive"]}.
     *
     * <p>Underscores are literal, unlike raw SQL {@code LIKE}. External ids are built out of them,
     * so a caller asking for {@code sap_work_orders} means that id and not
     * {@code sapXwork_orders}. See {@link WildcardPatterns}.
     *
     * <p>Matching is case-insensitive throughout, which is how external ids are compared
     * everywhere. Entries without a wildcard resolve through the indexed
     * {@code external_id_hash}; only the patterns need a scan, so the common exact lookup keeps
     * its index however many patterns share the list.
     *
     * <p>This replaced a separate {@code externalIdPrefix} field. Prefix was one loose match out of
     * several worth asking for, it could only be given once, and it could not be combined with the
     * exact list — {@code "sap_*"} says the same thing in the field that was already there.
     */
    @Size(max = 1000)
    @Schema(description = "Nodes matching any of these external ids. `*` and `%` are wildcards; `_` is literal.",
            example = "[\"sap_work_orders\", \"plant_*\"]")
    @SingleOrList
    private List<String> externalId;

    /**
     * Nodes whose name matches any entry. Same pattern rules as {@link #externalId}: {@code *} and
     * {@code %} are wildcards, everything else literal, case-insensitive. Entries OR together, so
     * one list can mix an exact name with a prefix.
     */
    @Size(max = 1000)
    @Schema(description = "Nodes whose name matches any of these patterns. `*` and `%` are wildcards.",
            example = "[\"SAP*\", \"Plant A\"]")
    @SingleOrList
    private List<String> name;

    /**
     * The data sources the node may have come from. A pattern list on the same rules as
     * {@link #externalId} and {@link #name}, so {@code ["sap", "opc_*"]} is one call rather than
     * the repeated ones a single-valued field forced.
     */
    @Size(max = 1000)
    @Schema(description = "Nodes whose source matches any of these patterns. `*` and `%` are wildcards.",
            example = "[\"sap\", \"opc_*\"]")
    @SingleOrList
    private List<String> source;

    /**
     * Labels the node must carry — <em>all</em> of them, like {@link #metadata} and unlike the
     * OR'd list fields above. Labels are a set the node either belongs to or does not, so "tagged
     * PUMP and CRITICAL" is the question worth asking; either-of is expressible as two calls.
     *
     * <p>Names are canonicalised before matching ({@link Labels#canonical}), so {@code "pump a"}
     * finds the label stored as {@code PUMP_A}. A name matching no label matches no nodes.
     *
     * <p><b>Keeps its plural name</b> while the OR'd fields around it went singular. Supplying a
     * second entry here narrows the result set where a second {@code name} or {@code source} would
     * widen it, and the plural is what marks that difference at the call site.
     */
    @Size(max = 1000)
    @Schema(description = "Nodes carrying all of these labels.", example = "[\"PUMP\", \"CRITICAL\"]")
    @SingleOrList
    private List<String> labels;

    /**
     * Metadata entries that must <em>all</em> be present. Several entries AND together ("has both
     * of these") rather than collapsing into one ambiguous match.
     *
     * <p><b>A null value matches the key alone</b>, whatever it carries: {@code {"health": null}}
     * asks for anything tagged {@code health}, and {@code {"health": "good", "tier": null}} asks
     * for both conditions at once. That is what {@code EventFilter} has always meant by a null
     * value; the node filters used to compare against SQL NULL instead, which is never true, so the
     * same body quietly matched nothing. One meaning across all four now.
     *
     * <p>It is also why {@code TimeseriesFilter} no longer carries a separate
     * {@code metadataKey}/{@code metadataValue} pair — that existed only because the map could not
     * express key-only matching.
     *
     * <p>Stays {@code metadata} rather than becoming singular: it is a map, not one of the
     * {@link SingleOrList} fields, so there is no scalar form for a singular name to read better in.
     */
    @Schema(description = "Metadata entries that must all be present. A null value matches the key alone.",
            example = "{\"owner\": \"plant-a\", \"health\": null}")
    private Map<String, String> metadata = new HashMap<>();

    private TimeFilter createdTime;

    private TimeFilter lastUpdatedTime;

    /**
     * The literal entries of {@link #externalId}, as the hashes actually stored on the row. These
     * are the ones that can use the index. Derived rather than sent, so the caller never has to
     * know how the hash is computed — and {@code @JsonIgnore}'d so deriving it does not leak an
     * {@code externalIdHashes} field into the request contract.
     *
     * <p>Plural, unlike the field it derives from: these accessors are internal query inputs rather
     * than wire fields, and they always hand back a list of every matching entry, so the singular
     * that suits a {@link SingleOrList} request field would misdescribe them.
     *
     * <p>Returns null when no external ids were supplied at all, so callers can tell "no
     * restriction" from "restricted to nothing". An empty list means every entry was a pattern.
     */
    @JsonIgnore
    public List<Long> getExternalIdHashes() {
        return FilterPatterns.exactExternalIdHashes(this.externalId);
    }

    /**
     * The wildcard entries of {@link #externalId}, as SQL {@code LIKE} patterns. Empty is the
     * common case — most callers look up exact ids — and then the query touches only the index.
     */
    @JsonIgnore
    public List<String> getExternalIdPatterns() {
        return FilterPatterns.wildcardPatterns(this.externalId);
    }

    /** {@link #name} as SQL {@code LIKE} patterns; a literal name yields a pattern matching it exactly. */
    @JsonIgnore
    public List<String> getNamePatterns() {
        return FilterPatterns.allPatterns(this.name);
    }

    /** {@link #source} as SQL {@code LIKE} patterns, on the same terms as {@link #getNamePatterns()}. */
    @JsonIgnore
    public List<String> getSourcePatterns() {
        return FilterPatterns.allPatterns(this.source);
    }

    /**
     * {@link #labels} as the hashes stored on {@code label.hash}, which carries the unique index —
     * so the join matches on it rather than on the text column. Derived and {@code @JsonIgnore}'d,
     * like {@link #getExternalIdHashes()}.
     */
    @JsonIgnore
    public List<Long> getLabelHashes() {
        if (this.labels == null) {
            return null;
        }
        return this.labels.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(Labels::hash)
                .toList();
    }
}
