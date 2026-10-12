// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.definition.FlowDefinition;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.definition.InvalidFlowException;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredVersion;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import ai.intellistream.datahub.timeseries.Timeseries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creating, changing and reading flows, with the access rules of the plan:
 * <ul>
 *   <li>seeing a flow, its versions and (later) its runs takes {@code /flows/read} and read access
 *       to the flow's dataset. A flow in a dataset the caller cannot read does not exist for them: a
 *       list leaves it out and a direct request answers 404;</li>
 *   <li>creating or changing one takes {@code /flows/write} and write access to its dataset — to
 *       both datasets when a change moves it.</li>
 * </ul>
 * What the caller may do comes from datahub-api ({@link CallerAccess}); dataset ids are resolved and
 * declared series created by calling the api as the caller, so the api's own ACL applies to those
 * as well.
 */
@Slf4j
@Service
public class FlowService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    private static final String CURSOR_PROPERTY = "externalId";

    private final FlowRepository repository;
    private final CallerAccess access;
    private final FlowValidator validator;
    private final TenantConfigService tenants;
    private final TenantConfigWriter tenantConfig;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    public FlowService(FlowRepository repository, CallerAccess access, FlowValidator validator,
                       TenantConfigService tenants, TenantConfigWriter tenantConfig,
                       TransactionTemplate transaction, JsonMapper json) {
        this.repository = repository;
        this.access = access;
        this.validator = validator;
        this.tenants = tenants;
        this.tenantConfig = tenantConfig;
        this.transaction = transaction;
        this.json = json;
    }

    public FlowView create(String body) {
        Caller caller = requireWrite();
        FlowDefinition definition = FlowDefinitions.parse(body);
        DatahubClient api = access.client();
        Prepared prepared = prepare(definition, api, caller);
        if (repository.find(definition.externalId()).isPresent()) {
            throw duplicate(definition.externalId());
        }
        List<String> created = createSeries(prepared, api);
        StoredFlow flow;
        try {
            flow = transaction.execute(status -> repository.create(definition.externalId(),
                    prepared.dataSetId(), definition.dataSet(), definition.name(), body, caller.subject()));
        } catch (DuplicateKeyException e) {
            throw duplicate(definition.externalId());
        }
        log.info("Flow {} created, version 1", definition.externalId());
        return FlowView.of(flow, parseJson(body), created);
    }

    public FlowView update(String externalId, String body) {
        Caller caller = requireWrite();
        StoredFlow existing = visible(caller, externalId);
        if (!caller.canWrite(existing.dataSetId())) {
            throw FlowProblems.datasetForbidden("You may not change flows in dataset "
                    + existing.dataSetExternalId() + ".");
        }
        FlowDefinition definition = FlowDefinitions.parse(body);
        if (!externalId.equals(definition.externalId())) {
            throw new InvalidFlowException(List.of(new ValidationProblem("#/externalId",
                    "Must be " + externalId + ", the flow being changed; a flow's external id does "
                            + "not change.")));
        }
        DatahubClient api = access.client();
        Prepared prepared = prepare(definition, api, caller);
        List<String> created = createSeries(prepared, api);
        StoredFlow flow = transaction.execute(status -> repository.addVersion(existing.id(),
                existing.currentVersion(), prepared.dataSetId(), definition.dataSet(),
                definition.name(), body, caller.subject()).orElse(null));
        if (flow == null) {
            throw FlowProblems.conflict("Flow " + externalId + " was changed by someone else since "
                    + "version " + existing.currentVersion() + ". Reload it and save again.");
        }
        log.info("Flow {} saved as version {}", externalId, flow.currentVersion());
        return FlowView.of(flow, parseJson(body), created);
    }

    /** Everything wrong with a definition, without saving it; and which series a save would create. */
    public FlowView.Validation validate(String body) {
        Caller caller = requireWrite();
        FlowDefinition definition;
        try {
            definition = FlowDefinitions.parse(body);
        } catch (InvalidFlowException e) {
            return validation(e.problems(), List.of());
        }
        DatahubClient api = access.client();
        try {
            Prepared prepared = prepare(definition, api, caller);
            List<String> missing = missingSeries(prepared, api).stream().map(DeclaredSeries.Series::externalId).toList();
            return validation(List.of(), missing);
        } catch (InvalidFlowException e) {
            return validation(e.problems(), List.of());
        }
    }

    public FlowView get(String externalId) {
        StoredFlow flow = visible(requireRead(), externalId);
        StoredVersion current = repository.version(flow.id(), flow.currentVersion()).orElseThrow();
        return FlowView.of(flow, parseJson(current.definition()), null);
    }

    public DataWrapper<FlowView> list(String cursor, Integer limit) {
        Caller caller = requireRead();
        int pageSize = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        if (pageSize > MAX_LIMIT) {
            throw FlowProblems.badRequest("limit is at most " + MAX_LIMIT + ".");
        }
        PageCursor after = PageCursor.decode(cursor);
        if (after != null && !after.matches(CURSOR_PROPERTY, false)) {
            throw FlowProblems.badRequest("The cursor belongs to another listing.");
        }
        Caller.DataSets readable = caller.admin() ? Caller.DataSets.ALL : caller.read();
        List<StoredFlow> page = repository.list(readable, after == null ? null : after.value(), pageSize);
        DataWrapper<FlowView> out = new DataWrapper<>();
        out.setItems(page.stream().map(flow -> FlowView.of(flow, null, null)).toList());
        if (page.size() == pageSize) {
            StoredFlow last = page.getLast();
            out.setNextCursor(new PageCursor(CURSOR_PROPERTY, false, last.externalId(),
                    String.valueOf(last.id())).encode());
        }
        return out;
    }

    public List<FlowView.Version> versions(String externalId) {
        StoredFlow flow = visible(requireRead(), externalId);
        return repository.versions(flow.id()).stream()
                .map(v -> new FlowView.Version(v.version(), v.createdBy(), v.createdAt(), null))
                .toList();
    }

    public FlowView.Version version(String externalId, int version) {
        StoredFlow flow = visible(requireRead(), externalId);
        StoredVersion v = repository.version(flow.id(), version).orElseThrow(() ->
                FlowProblems.notFound("Flow " + externalId + " has no version " + version + "."));
        return new FlowView.Version(v.version(), v.createdBy(), v.createdAt(), parseJson(v.definition()));
    }

    /** A definition checked and its datasets resolved: what saving it needs. */
    private record Prepared(long dataSetId, List<DeclaredSeries.Series> series, Map<String, Long> dataSets) {
    }

    /**
     * Validates a definition and resolves the datasets it names, as the caller.
     *
     * @throws InvalidFlowException listing everything wrong
     */
    private Prepared prepare(FlowDefinition definition, DatahubClient api, Caller caller) {
        List<ValidationProblem> problems = new ArrayList<>(validator.validate(definition, secretNames()));
        DeclaredSeries.Result declared = DeclaredSeries.of(definition, definition.dataSet());
        problems.addAll(declared.problems());
        if (!problems.isEmpty()) {
            throw new InvalidFlowException(problems);
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(definition.dataSet());
        declared.series().forEach(s -> names.add(s.dataSet()));
        Map<String, Long> dataSets = resolveDataSets(names, api);
        if (!dataSets.containsKey(definition.dataSet())) {
            throw new InvalidFlowException(List.of(new ValidationProblem("#/dataSet",
                    "No dataset has the external id " + definition.dataSet() + ".")));
        }
        long dataSetId = dataSets.get(definition.dataSet());
        if (!caller.canWrite(dataSetId)) {
            throw FlowProblems.datasetForbidden("You may not create or change flows in dataset "
                    + definition.dataSet() + ": that takes write access to it.");
        }
        for (DeclaredSeries.Series s : declared.series()) {
            Long id = dataSets.get(s.dataSet());
            if (id == null) {
                throw new InvalidFlowException(List.of(new ValidationProblem("#/processors",
                        "No dataset has the external id " + s.dataSet() + ", where series "
                                + s.externalId() + " would be created.")));
            }
            if (!caller.canWrite(id)) {
                throw FlowProblems.datasetForbidden("You may not create series in dataset "
                        + s.dataSet() + ": that takes write access to it.");
            }
        }
        return new Prepared(dataSetId, declared.series(), dataSets);
    }

    /** Creates the declared series that do not exist yet, as the caller; their external ids. */
    private List<String> createSeries(Prepared prepared, DatahubClient api) {
        List<DeclaredSeries.Series> missing = missingSeries(prepared, api);
        if (missing.isEmpty()) {
            return List.of();
        }
        List<Timeseries> create = new ArrayList<>();
        for (DeclaredSeries.Series s : missing) {
            Timeseries ts = new Timeseries();
            ts.setExternalId(s.externalId());
            ts.setName(s.name());
            ts.setUnit(s.unit());
            ts.setDescription(s.description());
            ts.setDataSetId(prepared.dataSets().get(s.dataSet()));
            ts.setValueType("float");
            create.add(ts);
        }
        try {
            api.timeseries().create(create);
        } catch (DatahubApiException e) {
            throw apiError(e, "Creating the flow's series");
        }
        List<String> ids = missing.stream().map(DeclaredSeries.Series::externalId).toList();
        log.info("Created {} series declared by a flow", ids.size());
        return ids;
    }

    private List<DeclaredSeries.Series> missingSeries(Prepared prepared, DatahubClient api) {
        if (prepared.series().isEmpty()) {
            return List.of();
        }
        Set<String> existing = new java.util.HashSet<>();
        try {
            List<IdCollection> ids = prepared.series().stream()
                    .map(s -> IdCollection.createFromExternalId(s.externalId())).toList();
            for (Timeseries t : api.timeseries().byIds(ids).getItems()) {
                existing.add(t.getExternalId());
            }
        } catch (DatahubApiException e) {
            throw apiError(e, "Looking up the flow's series");
        }
        return prepared.series().stream().filter(s -> !existing.contains(s.externalId())).toList();
    }

    private Map<String, Long> resolveDataSets(Set<String> externalIds, DatahubClient api) {
        Map<String, Long> out = new HashMap<>();
        try {
            List<IdCollection> ids = externalIds.stream().map(IdCollection::createFromExternalId).toList();
            for (DataSetModel ds : api.datasets().byIds(ids).getItems()) {
                if (externalIds.contains(ds.getExternalId())) {
                    out.put(ds.getExternalId(), ds.getId());
                }
            }
        } catch (DatahubApiException e) {
            throw apiError(e, "Looking up the flow's dataset");
        }
        return out;
    }

    /** The names of the tenant's flow secrets, which references to secrets are checked against. */
    private Set<String> secretNames() {
        Tenant tenant = tenants.getConfig(TenantContext.getTenantId());
        try {
            return TenantFlowSection.secretNames(tenantConfig.current(tenant.getOrganizationName()));
        } catch (IllegalStateException e) {
            log.warn("Could not read the tenant's flow secrets from Vault: {}", e.getMessage());
            throw FlowProblems.apiUnavailable("The flow's secrets could not be checked: the "
                    + "configuration store did not answer. Retry shortly.");
        }
    }

    private Caller requireRead() {
        Caller caller = access.current();
        if (!caller.admin() && !caller.flowsRead()) {
            throw FlowProblems.forbidden("Seeing flows takes the " + Caller.FLOWS_READ + " grant.");
        }
        return caller;
    }

    private Caller requireWrite() {
        Caller caller = access.current();
        if (!caller.admin() && !caller.flowsWrite()) {
            throw FlowProblems.forbidden("Changing flows takes the " + Caller.FLOWS_WRITE + " grant.");
        }
        return caller;
    }

    /** The flow, if the caller may see it; otherwise a 404, as if it did not exist. */
    private StoredFlow visible(Caller caller, String externalId) {
        return repository.find(externalId)
                .filter(flow -> caller.canRead(flow.dataSetId()))
                .orElseThrow(() -> FlowProblems.notFound("No flow " + externalId + "."));
    }

    private static FlowProblems.ProblemException duplicate(String externalId) {
        return FlowProblems.duplicate("A flow with external id " + externalId + " already exists.");
    }

    /** An api refusal while acting for the caller, as this service's answer. */
    private static FlowProblems.ProblemException apiError(DatahubApiException e, String what) {
        String detail = e.problem() != null && e.problem().detail() != null ? e.problem().detail() : e.getMessage();
        return switch (e.statusCode()) {
            case 400, 404 -> FlowProblems.badRequest(what + " failed: " + detail);
            case 403 -> FlowProblems.datasetForbidden(what + " was refused: " + detail);
            case 409 -> FlowProblems.conflict(what + " failed: " + detail);
            default -> CallerAccess.translate(e);
        };
    }

    private JsonNode parseJson(String body) {
        return json.readTree(body);
    }

    private static FlowView.Validation validation(List<ValidationProblem> problems, List<String> series) {
        return new FlowView.Validation(problems.isEmpty(),
                problems.stream().map(p -> new FlowView.Problem(p.pointer(), p.message())).toList(), series);
    }
}
