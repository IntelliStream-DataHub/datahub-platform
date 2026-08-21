// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.LeanTimeseries;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.api.services.UnitService;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.TimeseriesFields;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import ai.intellistream.datahub.transformers.TimeseriesTransformer;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * MCP tool wrappers for timeseries CRUD and datapoint I/O.
 *
 * <p>All tools execute under the caller's JWT, which means:
 * <ul>
 *     <li>Multi-tenant routing is automatic — {@code TenantContext} is set by
 *         {@code OrganizationValidator} during JWT validation and persists for the
 *         duration of the request thread. The downstream
 *         {@link TimeseriesService} honours it transparently.</li>
 *     <li>The security filter chain requires {@code ROLE_DATAHUB_ACCESS} on every
 *         non-public request (see {@code SecurityConfig}), and these tools run as MVC
 *         endpoints on that chain, so the role check is applied before any tool method
 *         executes — no additional per-method guard is needed.</li>
 * </ul>
 *
 * <p>Tool signatures favour single-item operations with scalar parameters — the LLM
 * can call the tool in a loop for batch work without having to construct nested
 * JSON payloads. The bulk endpoints on the REST API stay available for programmatic
 * callers that need them.
 */
@Component
@Slf4j
public class TimeseriesMcpTools {

    private final TimeseriesService timeseriesService;
    private final TimeseriesRepository timeseriesRepository;
    private final UnitService unitService;

    public TimeseriesMcpTools(
            TimeseriesService timeseriesService,
            TimeseriesRepository timeseriesRepository,
            UnitService unitService
    ) {
        this.timeseriesService = timeseriesService;
        this.timeseriesRepository = timeseriesRepository;
        this.unitService = unitService;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_create",
            description = """
                    Create a single timeseries. Returns the created timeseries including
                    the server-assigned id. The externalId is a stable, snake_case
                    human-readable identifier (3–256 chars, e.g. "reactor_1_temp_c");
                    the id is a server-assigned numeric primary key. A timeseries must
                    belong to an existing dataSet — look one up with dataset_search
                    if you don't already know the dataSetId. It must also carry a unit of
                    measure: supply unit, unitExternalId, or both — unit_list and unit_get
                    give you the catalogue to pick from. For a quantity with no physical
                    unit, say so explicitly (e.g. unit "count" or "ratio").
                    """
    )
    public DataWrapper<Timeseries> createTimeseries(
            @ToolParam(description = "Stable snake_case id. 3–256 chars, letters/digits/underscore.")
            String externalId,
            @ToolParam(description = "Human-readable display name.")
            String name,
            @ToolParam(description = "Id of the dataset this timeseries belongs to (required).")
            Long dataSetId,
            @ToolParam(required = false, description = "Optional description.")
            String description,
            @ToolParam(required = false, description =
                    "Value type: NUMERIC (default) or STRING.")
            String valueType,
            @ToolParam(required = false, description =
                    "Unit of measure, e.g. 'Celsius'. Required unless unitExternalId is given.")
            String unit,
            @ToolParam(required = false, description =
                    "External id of a unit in the catalogue, e.g. 'celsius' (see unit_list). "
                            + "Required unless unit is given.")
            String unitExternalId
    ) throws Exception {
        if (isBlank(unit) && isBlank(unitExternalId)) {
            // Timeseries.unit is @NotBlank and TimeseriesService.save() runs the validator itself,
            // so a unitless create dies deep in the service with a ConstraintViolationException the
            // model can make nothing of. Say what is missing, here, in terms of the tool's own
            // parameters.
            throw new IllegalArgumentException(
                    "A timeseries needs a unit of measure: supply 'unit' (e.g. 'Celsius') or "
                            + "'unitExternalId' (e.g. 'celsius'). Call unit_list to see the "
                            + "catalogue, or use a dimensionless unit such as 'count' or 'ratio'.");
        }

        String resolvedUnit = unit;
        if (!isBlank(unitExternalId)) {
            // unitExternalId alone is not enough for the validator: it only constrains 'unit'. The
            // console resolves the pair the same way (symbol into unit, externalId beside it), so
            // do that here rather than making the model send a symbol it would have to guess.
            var known = unitService.findByExternalId(unitExternalId);
            if (known == null) {
                throw new IllegalArgumentException(
                        "Unknown unit externalId '" + unitExternalId + "'. Call unit_list to see the "
                                + "catalogue, or pass a free-text 'unit' instead.");
            }
            if (isBlank(resolvedUnit)) {
                resolvedUnit = isBlank(known.getSymbol()) ? known.getName() : known.getSymbol();
            }
        }

        Timeseries ts = new Timeseries();
        ts.setExternalId(externalId);
        ts.setName(name);
        ts.setDataSetId(dataSetId);
        if (description != null) ts.setDescription(description);
        if (valueType != null) ts.setValueType(valueType);
        ts.setUnit(resolvedUnit);
        if (!isBlank(unitExternalId)) ts.setUnitExternalId(unitExternalId);

        var req = new DataWrapper<Timeseries>();
        req.getItems().add(ts);
        return timeseriesService.save(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_get",
            description = """
                    Look up a timeseries by its externalId or numeric id. Supply exactly
                    one of the two. Returns the timeseries (including its dataSetId, unit,
                    metadata) or an empty result if no match for this tenant.
                    """
    )
    public DataWrapper<Timeseries> getTimeseries(
            @ToolParam(required = false, description = "Stable snake_case external id.")
            String externalId,
            @ToolParam(required = false, description = "Numeric server-assigned id.")
            Long id
    ) {
        IdCollection ref = new IdCollection();
        if (id != null) ref.setId(id);
        else if (externalId != null) ref.setExternalId(externalId);
        else throw new IllegalArgumentException("Supply either externalId or id");
        return timeseriesService.byids(List.of(ref));
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_search",
            description = """
                    Full-text search for timeseries whose name, externalId or
                    description matches the query, ranked by relevance. Use when the caller
                    knows a topic but not a specific externalId. Capped by 'limit'
                    (default 100, max 1000).
                    """
    )
    public McpList<LeanTimeseries> searchTimeseries(
            @ToolParam(description = "Search text (matched against name/externalId/description).")
            String query,
            @ToolParam(required = false, description = "Max results (default 100, max 1000).")
            Integer limit
    ) {
        int cap = (limit == null) ? 100 : limit;
        SearchBody<TimeseriesFilter> cmd = new SearchBody<>();
        var sf = new SearchForm();
        sf.setQuery(query);
        cmd.setSearch(sf);
        // No filter: this tool searches by phrase only. (The field is now a real TimeseriesFilter
        // rather than the empty placeholder class, so leaving it null means "no narrowing".)
        cmd.setLimit(cap);
        List<LeanTimeseries> items = timeseriesService.search(cmd).getItems().stream()
                .map(LeanTimeseries::from).toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_send_datapoint",
            description = """
                    Append a single datapoint to a timeseries. Identify the target by
                    externalId OR id (supply exactly one). Timestamp accepts ISO-8601
                    (e.g. '2026-04-23T14:05:00Z') or epoch milliseconds as a string.
                    Value is the raw sample as a string — numeric series accept '12.34',
                    string series accept any text.
                    """
    )
    public DataWrapper<?> sendDatapoint(
            @ToolParam(required = false, description = "Target timeseries externalId.")
            String externalId,
            @ToolParam(required = false, description = "Target timeseries id.")
            Long id,
            @ToolParam(description = "ISO-8601 timestamp or epoch-ms as string.")
            String timestamp,
            @ToolParam(description = "Sample value as a string.")
            String value
    ) throws Exception {
        if ((externalId == null) == (id == null)) {
            throw new IllegalArgumentException("Supply exactly one of externalId or id");
        }
        var coll = new DatapointsCollection();
        if (id != null) coll.setId(id);
        if (externalId != null) coll.setExternalId(externalId);
        coll.setDatapoints(List.of(new DatapointString(timestamp, value)));

        var req = new DataWrapper<DatapointsCollection>();
        req.getItems().add(coll);
        return timeseriesService.insertDatapoints(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_get_latest",
            description = """
                    Fetch the most recent datapoint for a timeseries. Useful for "what's
                    the current reading?" queries. Identify the target by externalId or id.
                    """
    )
    public DataWrapper<?> getLatestDatapoint(
            @ToolParam(required = false, description = "Target timeseries externalId.")
            String externalId,
            @ToolParam(required = false, description = "Target timeseries id.")
            Long id
    ) throws Exception {
        IdCollection ref = new IdCollection();
        if (id != null) ref.setId(id);
        else if (externalId != null) ref.setExternalId(externalId);
        else throw new IllegalArgumentException("Supply either externalId or id");

        var req = new DataWrapper<IdCollection>();
        req.getItems().add(ref);
        return timeseriesService.fetchLatestDatapoint(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_update",
            description = """
                    Update common fields on a single timeseries. Identify by id. Only the
                    provided fields are set; null/omitted fields are left alone. For niche
                    patch operations (metadata map add/remove, security categories), use
                    the REST API directly.
                    """
    )
    public DataWrapper<Timeseries> updateTimeseries(
            @ToolParam(description = "Id of the timeseries to update.")
            Long id,
            @ToolParam(required = false, description = "New display name.")
            String newName,
            @ToolParam(required = false, description = "New description.")
            String newDescription,
            @ToolParam(required = false, description = "New snake_case externalId.")
            String newExternalId,
            @ToolParam(required = false, description = "New unit display string, e.g. 'Celsius'.")
            String newUnit,
            @ToolParam(required = false, description = "New unit externalId from the unit catalogue.")
            String newUnitExternalId
    ) throws Exception {
        UpdateTimeseries ut = new UpdateTimeseries().setId(id);
        TimeseriesFields f = new TimeseriesFields();
        if (newName != null) f.setName(new UpdateStringField().set(newName));
        if (newDescription != null) f.setDescription(new UpdateStringField().set(newDescription));
        if (newExternalId != null) f.setExternalId(new UpdateStringField().set(newExternalId));
        if (newUnit != null) f.setUnit(new UpdateStringField().set(newUnit));
        if (newUnitExternalId != null) f.setUnitExternalId(new UpdateStringField().set(newUnitExternalId));
        ut.setUpdate(f);

        DataWrapper<UpdateTimeseries> req = new DataWrapper<>();
        req.getItems().add(ut);
        return timeseriesService.updateTimeseries(req);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_list",
            description = """
                    List timeseries available to the caller. Use for browsing when you
                    don't yet have a search term. Results are capped by 'limit'. Edges
                    are not populated — use timeseries_get or resource_fetch_related
                    if you need connections.
                    """
    )
    @Transactional(readOnly = true)
    public McpList<LeanTimeseries> listTimeseries(
            @ToolParam(required = false, description = "Max results (default 100, max 1000).")
            Integer limit
    ) {
        int cap = (limit == null) ? 100 : Math.min(limit, 1000);
        var entities = timeseriesRepository.list(cap);
        List<LeanTimeseries> items = TimeseriesTransformer.from(entities).stream()
                .map(LeanTimeseries::from).toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_delete",
            description = """
                    Delete a single timeseries by id or externalId. Deletes only the
                    timeseries definition and its metadata — existing datapoints in
                    ClickHouse are swept by the downstream consumer.
                    """
    )
    public String deleteTimeseries(
            @ToolParam(required = false, description = "Target externalId.")
            String externalId,
            @ToolParam(required = false, description = "Target id.")
            Long id
    ) throws Exception {
        IdCollection ref = new IdCollection();
        if (id != null) ref.setId(id);
        else if (externalId != null) ref.setExternalId(externalId);
        else throw new IllegalArgumentException("Supply either externalId or id");

        DataWrapper<IdCollection> req = new DataWrapper<>();
        req.getItems().add(ref);
        timeseriesService.deleteTimeseries(req);
        return "deleted";
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "timeseries_fetch_datapoints",
            description = """
                    Fetch historical datapoints for a single timeseries between start and
                    end timestamps. Times are ISO-8601 strings (e.g. '2026-04-01T00:00:00Z').
                    Results are capped by 'limit' — default 1000, hard max 100000.

                    Omit 'aggregates' to get raw samples. To get aggregated values per time
                    bucket, supply 'aggregates' (comma-separated; allowed: avg, sum, min, max)
                    together with a 'granularity' bucket size (e.g. '1h', '1d') — for example
                    aggregates='avg,max', granularity='1h' returns the hourly average and
                    maximum.
                    """
    )
    public DataWrapper<DataCollection<?>> fetchDatapoints(
            @ToolParam(required = false, description = "Target externalId.")
            String externalId,
            @ToolParam(required = false, description = "Target id.")
            Long id,
            @ToolParam(description = "Start of range (inclusive), ISO-8601.")
            String start,
            @ToolParam(description = "End of range (exclusive), ISO-8601.")
            String end,
            @ToolParam(required = false, description = "Max datapoints per timeseries (default 1000).")
            Integer limit,
            @ToolParam(required = false, description =
                    "Comma-separated aggregates (avg, sum, min, max). Requires 'granularity'.")
            String aggregates,
            @ToolParam(required = false, description = "Aggregation bucket size, e.g. '1h', '1d'.")
            String granularity
    ) {
        if ((externalId == null) == (id == null)) {
            throw new IllegalArgumentException("Supply exactly one of externalId or id");
        }

        RetrieveFilter filter = new RetrieveFilter();
        if (id != null) filter.setId(id);
        if (externalId != null) filter.setExternalId(externalId);
        filter.setStart(ZonedDateTime.parse(start));
        filter.setEnd(ZonedDateTime.parse(end));
        if (limit != null) filter.setLimit(limit);
        if (aggregates != null && !aggregates.isBlank()) {
            filter.setAggregates(
                    Arrays.stream(aggregates.split(","))
                            .map(s -> s.trim().toLowerCase())
                            .filter(s -> !s.isEmpty())
                            .toList()
            );
        }
        if (granularity != null) filter.setGranularity(granularity);

        DataRetriever<RetrieveFilter> req = new DataRetriever<>();
        req.setItems(List.of(filter));
        req.setStart(start);
        req.setEnd(end);
        req.setLimit(limit == null ? 1000 : limit);
        return timeseriesService.retrieveDatapointsFromCH(req);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
