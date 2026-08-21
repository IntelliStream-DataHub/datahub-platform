// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.dto.UUIDAndBigIntHash;
import ai.intellistream.datahub.jpa.dto.UUIDAndBigIntHashImpl;
import ai.intellistream.datahub.models.DataSort;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.datafilters.FilterPatterns;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.models.datafilters.SqlField;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.events.*;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.repositories.event.DatasetValue;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantConfigService;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.value.ClickHouseArrayValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.ZoneOffset;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static ai.intellistream.datahub.helpers.EventHelper.EVENT_COLUMNS;


@Service
@Slf4j
public class ClickHouseEventService extends ClickHouseService {

    private final String TN = "events";

    public ClickHouseEventService(TenantConfigService tenantConfigService, ValkeyService valkeyService, ClickHouseClientPool clickHouseClientPool) {
        super(tenantConfigService, valkeyService, clickHouseClientPool);
    }

    public void createEvents(EventCudMessage message) {
        Client client = getClickhouseClient(message.getTenantId());
        try {

            PipedOutputStream rawPipeOut = new PipedOutputStream();
            PipedInputStream pIn = new PipedInputStream(rawPipeOut, BUFFER_SIZE);

            // Start the writer thread FIRST to avoid deadlock.
            // The pipe must have data (or be closed) for the reader to proceed.
            CompletableFuture<Void> writer = CompletableFuture
                    .runAsync(() -> writeDataToStream(rawPipeOut, message))
                    .exceptionally(ex -> {
                        log.error("Background data writer failed for table {}", TN, ex);
                        // Defensive close: if writeDataToStream fails before its
                        // try-with-resources can close the pipe, we must close it here or the
                        // reader blocks forever.
                        closeQuietly(rawPipeOut);
                        return null;
                    });
            try (InsertResponse r = client.insert(TN, pIn, ROWBIN, getSettings()).get(30, TimeUnit.SECONDS)) {
                log.debug("Rows Written! Server Time: {}", r.getServerTime());

            } catch (Exception e) {
                log.error("Failed to insert data into ClickHouse table: {}", TN, e);
                throw new RuntimeException("ClickHouse insert failed", e);
            } finally {
                // On reader failure (timeout, remote error) close the pipe so the writer can't
                // keep running past this call, then await its termination.
                closeQuietly(rawPipeOut);
                writer.join();
            }

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void writeDataToStream(PipedOutputStream rawPipeOut, EventCudMessage message) {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(rawPipeOut))) {
            for(EventModel eventModel : message.getEvents()) {

                ClickHouseHelper.writeUuid(dos, UUID.fromString(eventModel.getId()));
                ClickHouseHelper.writeString(dos, eventModel.getExternalId());

                var hash = IdGenerator.generate128bitKey(eventModel.getExternalId(), message.getTenantId());
                ClickHouseHelper.writeInt128(dos, hash);

                ClickHouseHelper.writeString(dos, eventModel.getType());

                // In ClickHouse RowBinary format, Nullable columns require an extra 1-byte prefix before the
                // actual data: 0 if the value is NOT NULL. 1 if the value is NULL
                // and then no data bytes follow for that column.

                // sub_type: Nullable(String)
                if (eventModel.getSubType() == null) {
                    dos.writeByte(1);
                } else {
                    dos.writeByte(0);
                    ClickHouseHelper.writeString(dos, eventModel.getSubType());
                }

                // status: Nullable(String)
                if (eventModel.getStatus() == null) {
                    dos.writeByte(1);
                } else {
                    dos.writeByte(0);
                    ClickHouseHelper.writeString(dos, eventModel.getStatus());
                }

                ClickHouseHelper.writeString(dos, eventModel.getDescription());

                // data_set_id is Int64 (non-nullable) on the CH side but optional on the API DTO
                // (events emitted by platform-internal sources may have no owning dataset). Write 0
                // as a sentinel for "no dataset" — dataset ids are sequential PK values starting at
                // 1, so 0 never collides with a real id.
                Long dataSetId = eventModel.getDataSetId();
                dos.writeLong(Long.reverseBytes(dataSetId == null ? 0L : dataSetId));

                ClickHouseHelper.writeString(dos, eventModel.getSource());

                long dateCreatedEpoch = DateTimeHandler.toEpochUTCTime(eventModel.getCreatedTime());
                dos.writeLong(Long.reverseBytes(dateCreatedEpoch));

                long lastUpdatedEpoch = DateTimeHandler.toEpochUTCTime(eventModel.getLastUpdatedTime());
                dos.writeLong(Long.reverseBytes(lastUpdatedEpoch));

                long eventTimeEpoch = DateTimeHandler.toEpochUTCTime(eventModel.getEventTime());
                dos.writeLong(Long.reverseBytes(eventTimeEpoch));

                // The three related-resource columns are a denormalization of one API-level list,
                // so they are always derived together and always written together.
                var relatedResources = RelatedResourceColumns.from(eventModel.getRelatedResources());
                ClickHouseHelper.writeArrayInt64(dos, relatedResources.ids());
                ClickHouseHelper.writeArrayString(dos, relatedResources.externalIds());
                ClickHouseHelper.writeArrayInt64(dos, relatedResources.externalIdHashes());
                ClickHouseHelper.writeMapStringString(dos, eventModel.getMetadata());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing data to ClickHouse pipe", e);
        }
    }

    public void updateEvents(EventCudMessage message) {

        Client client = getClickhouseClient(message.getTenantId());

        // The related-resource columns are written from the RESOLVED models the API published
        // alongside the patch forms, never from the raw form. The form holds only the caller's
        // set/add/remove verbs on one side of a relation; the model holds the merged, fully
        // resolved list. Deriving all three columns from it is what keeps them consistent — the
        // old code built them from the form and so could update the external-id columns while
        // leaving related_resources_id stale.
        Map<UUID, EventModel> resolvedById = message.getEvents().stream()
                .filter(it -> it.getId() != null)
                .collect(Collectors.toMap(it -> UUID.fromString(it.getId()), it -> it, (a, b) -> b));

        try {
            for (UpdateEventForm updateEntry : message.getUpdateEvents()) {
                List<String> setClauses = new ArrayList<>();
                Map<String, Object> params = new HashMap<>();
                var fields = updateEntry.getUpdate();

                String whereClause;
                if (updateEntry.getId() != null) {
                    whereClause = "id = {where_id:UUID}";
                    params.put("where_id", updateEntry.getId());
                } else if (updateEntry.getExternalId() != null) {
                    // Signed, and Int128, because that is what the column is. The key is an
                    // unsigned 128-bit value, so every key above 2^127 is stored as its negative
                    // two's-complement twin — binding the unsigned form matched none of those, and
                    // an update by externalId silently hit nothing for half of all ids.
                    var hash = IdGenerator.generate128bitKeySigned(updateEntry.getExternalId(), message.getTenantId());
                    whereClause = "external_id_hash = {where_hash:Int128}";
                    params.put("where_hash", hash);
                } else {
                    log.warn("Skipping event update because no ID or externalId was provided.");
                    continue;
                }

                if (fields.getExternalId() != null && fields.getExternalId().getSet() != null) {
                    String newExtId = fields.getExternalId().getSet();
                    setClauses.add("external_id = {ext_id:String}");
                    params.put("ext_id", newExtId);

                    // Same signed form the insert path writes: ClickHouseHelper.writeInt128 stores
                    // the raw low 128 bits, so a rename must land on the same number an insert would
                    // have produced, or the row becomes unfindable by its own new externalId.
                    setClauses.add("external_id_hash = {ext_hash:Int128}");
                    params.put("ext_hash", IdGenerator.generate128bitKeySigned(newExtId, message.getTenantId()));
                }
                if (fields.getType() != null && fields.getType().getSet() != null) {
                    setClauses.add("type = {type:String}");
                    params.put("type", fields.getType().getSet());
                }
                if (fields.getSubType() != null && fields.getSubType().getSet() != null) {
                    setClauses.add("sub_type = {sub_type:String}");
                    params.put("sub_type", fields.getSubType().getSet());
                }
                if (fields.getStatus() != null && fields.getStatus().getSet() != null) {
                    setClauses.add("status = {status:String}");
                    params.put("status", fields.getStatus().getSet());
                }
                if (fields.getDescription() != null && fields.getDescription().getSet() != null) {
                    setClauses.add("description = {desc:String}");
                    params.put("desc", fields.getDescription().getSet());
                }
                if (fields.getSource() != null && fields.getSource().getSet() != null) {
                    setClauses.add("source = {source:String}");
                    params.put("source", fields.getSource().getSet());
                }
                // No event_time clause: the table is PARTITION BY toYYYYMM(event_time) and a
                // mutation cannot move a row between partitions, so ClickHouse refuses the update
                // outright (CANNOT_UPDATE_COLUMN). The field is gone from EventFields for the same
                // reason; this comment is here so nobody adds the clause back from the model.
                var relatedResourceUpdate = fields.getRelatedResources();
                if (relatedResourceUpdate != null
                        && (relatedResourceUpdate.getSet() != null
                            || relatedResourceUpdate.getAdd() != null
                            || relatedResourceUpdate.getRemove() != null)) {
                    EventModel resolved = updateEntry.getId() == null ? null : resolvedById.get(updateEntry.getId());
                    if (resolved == null) {
                        // Writing a partial related-resource update is exactly the drift this
                        // model removes, so fail rather than update two columns out of three.
                        throw new IllegalStateException(
                                "No resolved event published for related-resource update of event " + updateEntry.getId());
                    }
                    var relatedResources = RelatedResourceColumns.from(resolved.getRelatedResources());

                    setClauses.add("related_resources_id = {rel_ids:Array(Int64)}");
                    params.put("rel_ids", relatedResources.ids());

                    // Array(String) params must arrive as a quoted ClickHouse array literal — the
                    // client does not quote the elements of a raw Collection<String>, so the
                    // server fails to parse it. Same treatment as the id list in deleteEvents().
                    setClauses.add("related_resources_external_id = {rel_ext_ids:Array(String)}");
                    params.put("rel_ext_ids", toChStringArray(relatedResources.externalIds()));

                    setClauses.add("related_resources_external_id_hash = {rel_ext_hashes:Array(Int64)}");
                    params.put("rel_ext_hashes", relatedResources.externalIdHashes());
                }
                // Metadata has three modes and all three have to reach storage. `set` replaces the
                // map; `add` and `remove` are partial edits and are expressed as map functions over
                // the stored value rather than as a read-modify-write. That is not just tidier: this
                // mutation runs asynchronously on the consumer, so reading the map here and writing
                // a merged one back would overwrite anything a concurrent update did in between,
                // and the caller who asked to add one key would silently lose someone else's.
                //
                // set wins over add/remove when more than one is supplied — replacing the map and
                // then editing the replacement is the only reading of that combination that makes
                // sense, and it is what the in-memory path in EventService.validateAndUpdate does.
                if (fields.getMetadata() != null) {
                    var metadataField = fields.getMetadata();
                    String metadataExpr = null;

                    if (metadataField.getSet() != null) {
                        metadataExpr = "{metadata:Map(String, String)}";
                        params.put("metadata", toChStringMap(metadataField.getSet()));
                    }
                    if (metadataField.getAdd() != null && !metadataField.getAdd().isEmpty()) {
                        // mapUpdate(base, patch): patch's keys overwrite base's, base's others survive.
                        metadataExpr = "mapUpdate(" + (metadataExpr == null ? "metadata" : metadataExpr)
                                + ", {metadataAdd:Map(String, String)})";
                        params.put("metadataAdd", toChStringMap(metadataField.getAdd()));
                    }
                    if (metadataField.getRemove() != null && !metadataField.getRemove().isEmpty()) {
                        metadataExpr = "mapFilter((k, v) -> NOT has({metadataRemove:Array(String)}, k), "
                                + (metadataExpr == null ? "metadata" : metadataExpr) + ")";
                        params.put("metadataRemove", toChStringArray(metadataField.getRemove()));
                    }

                    if (metadataExpr != null) {
                        setClauses.add("metadata = " + metadataExpr);
                    }
                }

                setClauses.add("last_updated = now()");

                if (setClauses.size() <= 1) { // Only last_updated
                    continue;
                }

                String query = String.format("ALTER TABLE %s UPDATE %s WHERE %s", TN, String.join(", ", setClauses), whereClause);
                try (var response = client.query(query, params).get(10, TimeUnit.SECONDS)) {
                    log.debug("Update mutation submitted. Server response time: {} ms, Query ID: {}",
                            response.getServerTime(), response.getQueryId()
                    );
                } catch (Exception e) {
                    log.error("Failed to execute ClickHouse update query: {}", query, e);
                    throw new RuntimeException("ClickHouse update mutation failed", e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to update events in ClickHouse", e);
            throw new RuntimeException(e);
        }

    }

    /**
     * Renders a string collection as a ClickHouse array literal ({@code ['a','b']}) for binding to
     * an {@code Array(String)} query parameter. Backslashes and single quotes are escaped so a
     * value can never terminate the literal early.
     */
    /**
     * A {@code Map(String, String)} parameter as a quoted ClickHouse map literal. The counterpart of
     * {@link #toChStringArray(Collection)} and needed for the same reason: the client renders a raw
     * {@code Map} with {@code toString()}, which produces {@code {k=v}} — not valid ClickHouse — and
     * the server rejects it with CANNOT_PARSE_QUOTED_STRING.
     */
    static String toChStringMap(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + quote(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static String toChStringArray(Collection<String> values) {
        return values.stream()
                .map(ClickHouseEventService::quote)
                .collect(Collectors.joining(",", "[", "]"));
    }

    public void deleteEvents(EventCudMessage message) {

        List<UUID> idList = message.getEvents().stream()
                .map(EventModel::getId)
                .filter(Objects::nonNull)
                .map(UUID::fromString)
                .toList();

        if (idList.isEmpty()) {
            log.debug("No event IDs provided for deletion in tenant: {}", message.getTenantId());
            return;
        }

        Client client = getClickhouseClient(message.getTenantId());
        try {
            String query = "DELETE FROM events WHERE id IN {ids:Array(UUID)}";

            String formattedIds = idList.stream()
                    .map(id -> "'" + id.toString() + "'")
                    .collect(Collectors.joining(",", "[", "]"));
            Map<String, Object> params = Map.of("ids", formattedIds);

            try (var response = client.query(query, params).get(30, TimeUnit.SECONDS)) {
                log.debug("Lightweight delete for {} events submitted. Server Time: {} ms, Query ID: {}",
                        idList.size(),
                        response.getServerTime(),
                        response.getQueryId());
            }
        } catch (Exception e) {
            log.error("Failed to execute ClickHouse delete for table {} in tenant {}", TN, message.getTenantId(), e);
            throw new RuntimeException("ClickHouse event deletion failed", e);
        }
    }

    /**
     * Builds a dataset-ACL boolean condition for ClickHouse (no leading {@code AND}/{@code WHERE}).
     * The ids are bound as an {@code Array(Int64)} parameter named {@code aclDs} rather than inlined,
     * so the SQL never contains caller-derived values. Returns:
     * <ul>
     *   <li>{@code null} when {@code allowed == null} — the caller may read every dataset (no clause);</li>
     *   <li>{@code "1=0"} when {@code allowed} is empty — no readable datasets, match nothing;</li>
     *   <li>{@code "<column> IN {aclDs:Array(Int64)}"} otherwise, binding the ids into {@code params}.</li>
     * </ul>
     */
    private static String datasetAclCondition(Collection<Long> allowed, String column, Map<String, Object> params) {
        if (allowed == null) return null;
        if (allowed.isEmpty()) return "1=0";
        params.put("aclDs", new ArrayList<>(allowed));
        return column + " IN {aclDs:Array(Int64)}";
    }

    /**
     * The dataset condition for a caller-supplied {@code EventFilter.dataSetIds}, or {@code null}
     * when the filter does not restrict by dataset. Shared by the drill-down ({@link #filter}) and
     * aggregate ({@link #buildMcpEventWhere}) paths so the two cannot drift.
     *
     * <p>Ids match exactly <em>here</em>: the hierarchy expansion happens upstream, in
     * {@code EventService.resolveDataSetIds}, which replaces the caller's data sets with their
     * {@code BELONGS_TO} closure before the filter reaches this layer. This module cannot reach the
     * node tables, which is why the expansion cannot live here.
     *
     * <p>References are expected to carry ids by the time they reach here: {@code EventService}
     * resolves any given by {@code externalId} first, since only it can reach the node tables. One
     * that still has no id contributes nothing — which can only ever narrow the query, never widen
     * it, so a caller that skips the resolution step gets too few events rather than too many.
     *
     * <p>An <strong>empty</strong> list is {@code 1=0}, not "no restriction": the caller asked to be
     * narrowed to a set of datasets and that set is empty, so nothing may match. Dropping the
     * predicate instead would widen the query to every dataset the caller can read — the opposite of
     * what was asked. A JSON {@code null} never arrives here as an empty list;
     * {@code IdCollectionListDeserializer} keeps null null, which is what makes the two
     * distinguishable at all. Same treatment as {@link #datasetAclCondition}, one AND-term over.
     */
    private static String dataSetIdCondition(Collection<IdCollection> dataSetIds, String column,
                                             Map<String, Object> params) {
        if (dataSetIds == null) return null;
        List<Long> ids = dataSetIds.stream().map(IdCollection::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return "1=0";
        params.put("ds_ids", ids);
        return column + " IN {ds_ids:Array(Int64)}";
    }

    public <T> List<T> findAllById(Set<UUID> idList, String tenantId, Class<T> type) {
        return findAllById(idList, tenantId, type, null);
    }

    /**
     * Dataset-ACL-aware variant: {@code allowedDataSetIds == null} returns all matches (read-all
     * caller); otherwise only events whose {@code data_set_id} is in the set are returned.
     */
    public <T> List<T> findAllById(Set<UUID> idList, String tenantId, Class<T> type, Collection<Long> allowedDataSetIds) {
        List<T> results = new ArrayList<>();
        if (idList == null || idList.isEmpty()) {
            return results;
        }

        // Determine which columns to select based on the target type
        String columns = String.join(", ", EVENT_COLUMNS);
        // Note: EVENT_ONLY_ID_COLUMNS isn't provided in context,
        // but typically used for lightweight UUIDAndBigIntHash mapping.

        String formattedIds = idList.stream()
                .map(id -> "'" + id.toString() + "'")
                .collect(Collectors.joining(",", "[", "]"));
        Map<String, Object> params = new HashMap<>();
        params.put("ids", formattedIds);
        String aclCondition = datasetAclCondition(allowedDataSetIds, "e.data_set_id", params);
        String query = String.format("SELECT %s FROM events e WHERE e.id IN {ids:Array(UUID)}%s",
                columns, aclCondition == null ? "" : " AND " + aclCondition);

        Client client = getClickhouseClient(tenantId);
        try {
            Collection<GenericRecord> records = client.queryAll(query, params);

            for (GenericRecord r : records) {
                if (EventModel.class.isAssignableFrom(type)) {
                    results.add(type.cast(createEventModel(r)));
                } else if (UUIDAndBigIntHash.class.isAssignableFrom(type)) {
                    // Use the specific DTO for ID/Hash lookups
                    UUIDAndBigIntHash instance = new UUIDAndBigIntHashImpl();
                    instance.setId(r.getUUID("id"));
                    instance.setExternalId(r.getString("external_id"));
                    instance.setExternalIdHash(r.getBigInteger("external_id_hash"));
                    results.add(type.cast(instance));
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch events by ID from ClickHouse", e);
            throw new RuntimeException("ClickHouse lookup failed", e);
        }

        return results;
    }

    /**
     * The dataset an event belongs to, or null when it belongs to none.
     *
     * <p>Undoes the write path's sentinel. {@code data_set_id} is a non-nullable {@code Int64} in
     * ClickHouse while {@code EventModel.dataSetId} is optional, so a dataset-less event is stored
     * as 0 (see the binary writer above); a non-nullable column never reads back null, so without
     * this every such event comes off the wire claiming to belong to dataset 0.
     *
     * <p>That is not a cosmetic difference. A client that reads an event and writes it back — the
     * findings queue resolving a finding, an SDK caller patching one — faithfully sends the 0 it was
     * given and gets {@code 400 No dataset with following id exists!}, having changed nothing about
     * the field. Mapping it here keeps the sentinel an implementation detail of the storage layer,
     * which is the only place that needs to know about it.
     */
    private static Long dataSetIdOrNull(Long stored) {
        return stored == null || stored == 0L ? null : stored;
    }

    public EventModel createEventModel(GenericRecord r) {
        EventModel em = new EventModel();
        em.setId( r.getString("id") );
        em.setExternalId( r.getString("external_id") );
        em.setSource( r.getString("source") );
        em.setDataSetId( dataSetIdOrNull(r.getLong("data_set_id")) );
        em.setDescription( r.getString("description") );
        em.setType( r.getString("type") );
        em.setSubType( r.getString("sub_type") );
        em.setStatus( r.getString("status") );
        em.setCreatedTime( r.getZonedDateTime("date_created"));
        em.setLastUpdatedTime( r.getZonedDateTime("last_updated") );
        em.setEventTime( r.getZonedDateTime("event_time") );
        em.setRelatedResources( RelatedResourceColumns.zip(
                r.getList("related_resources_id"), r.getList("related_resources_external_id")) );
        em.setMetadata( (Map<String, String>)(r.getObject("metadata")) );
        return em;
    }

    public EventModel createEventModel(Map<String, Object> r, ClickHouseBinaryFormatReader reader) {
        EventModel em = new EventModel();
        em.setId(reader.getUUID("id").toString());
        em.setExternalId((String) r.get("external_id"));
        em.setSource( (String) r.get("source") );
        // data_set_id may come back as Integer (Int32) or Long (Int64) depending on the table's
        // column width; read it width-agnostically so the binary-reader path can't ClassCastException.
        Object dsId = r.get("data_set_id");
        em.setDataSetId( dsId == null ? null : dataSetIdOrNull(((Number) dsId).longValue()) );
        em.setDescription((String) r.get("description"));
        em.setType((String) r.get("type"));
        em.setSubType( (String)r.get("sub_type") );
        em.setStatus( (String)r.get("status") );
        em.setCreatedTime( reader.getZonedDateTime("date_created"));
        em.setLastUpdatedTime( reader.getZonedDateTime("last_updated") );
        em.setEventTime( reader.getZonedDateTime("event_time") );
        em.setRelatedResources( RelatedResourceColumns.zip(
                reader.getList("related_resources_id"), reader.getList("related_resources_external_id")) );
        em.setMetadata( (Map<String, String>)(r.get("metadata")) );
        return em;
    }

    /**
     * The plain AND-ed criteria an {@link EventFilter} contributes, appended to {@code criterias}
     * with their values bound into {@code params}.
     *
     * <p>Shared by {@link #filter} and {@link #search} so the two agree field for field: a filter
     * means the same thing whether it arrives beside a phrase or on its own. Deliberately excludes
     * everything that is not the filter — sort, cursor, advanced filter and the dataset ACL — since
     * those differ per caller.
     *
     * @return false when the filter cannot match anything, in which case the caller should return
     *         an empty result without querying
     */
    private boolean collectFilterCriteria(EventFilter filter, Map<String, Object> params,
                                          List<SqlField> criterias) {
        if(!filter.getRelatedResources().isEmpty()){
            Set<Long> relatedResourceIds = new HashSet<>();
            Set<Long> relatedExternalResourceIds = new HashSet<>();

            filter.getRelatedResources().forEach( it -> {
                if(it.getId() != null){
                    relatedResourceIds.add(it.getId());
                } else if(it.getExternalId() != null){
                    long hash = ExternalIds.hash(it.getExternalId());
                    relatedExternalResourceIds.add(hash);
                }
            });

            if(relatedResourceIds.isEmpty() && relatedExternalResourceIds.isEmpty()){
                // Every entry named neither an id nor an externalId. The caller asked to be
                // narrowed to those resources, so match nothing rather than dropping the predicate.
                return false;
            }

            // ClickHouse uses {name:Type} parameter syntax, not JDBC ':name'.
            // Build the WHERE clause inline so it always matches whichever params are bound.
            String criteria;
            if(relatedResourceIds.isEmpty()){
                criteria = "hasAll(e.related_resources_external_id_hash, {relatedExternalResourceIds:Array(Int64)})";
                params.put("relatedExternalResourceIds", relatedExternalResourceIds);
            } else if(relatedExternalResourceIds.isEmpty()){
                criteria = "hasAll(e.related_resources_id, {rel_ids:Array(Int64)})";
                params.put("rel_ids", relatedResourceIds);
            } else {
                criteria = "hasAll(e.related_resources_id, {rel_ids:Array(Int64)})"
                        + " AND hasAll(e.related_resources_external_id_hash, {relatedExternalResourceIds:Array(Int64)})";
                params.put("rel_ids", relatedResourceIds);
                params.put("relatedExternalResourceIds", relatedExternalResourceIds);
            }
            criterias.add( new SqlField("relatedResources", filter.getRelatedResources(), criteria) );
        }

        if(!filter.getMetadata().isEmpty()){
            int i = 1;
            for(Map.Entry<String, String> entry : filter.getMetadata().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if(key == null && value == null){
                    i++;
                    continue;
                }

                // The flattened-key/value arrays carry the indexes, so we query through
                // mapKeys/mapValues rather than metadata[key] to keep the index usable.
                String criteria;
                if(value == null){
                    criteria = "arrayExists(k -> (k = {key"+i+":String}), mapKeys(metadata))";
                    params.put("key"+i, key);
                } else if(key == null){
                    criteria = "arrayExists(v -> (v = {value"+i+":String}), mapValues(metadata))";
                    params.put("value"+i, value);
                } else {
                    criteria = "arrayExists(k -> (k = {key"+i+":String}), mapKeys(metadata)) AND arrayExists(v -> (v = {value"+i+":String}), mapValues(metadata))";
                    params.put("key"+i, key);
                    params.put("value"+i, value);
                }
                criterias.add( new SqlField("metadata", entry, criteria) );
                i++;
            }
        }

        // Literal entries go through the indexed hash column, wildcard entries need an ILIKE
        // over the text column; anyOf builds the OR and binds every value as a named param.
        // ILIKE, not LIKE: external ids are stored verbatim but matched case-insensitively, so
        // a search agrees with lookup instead of missing rows that differ only in case.
        String externalIdCondition = anyOf(externalIdHashes(filter), filter.getExternalIdPatterns(),
                "external_id_hash", "external_id", "ext", params);
        if(externalIdCondition != null){
            criterias.add( new SqlField("external_id", filter.getExternalId(), externalIdCondition + " ") );
        }
        // Source has no hashed column to fall back on, so every entry is a pattern — a literal
        // one still matches exactly, because the escaping makes it so.
        String sourceCondition = anyOf(null, filter.getSourcePatterns(), null, "source", "src", params);
        if(sourceCondition != null){
            criterias.add( new SqlField("source", filter.getSource(), sourceCondition + " ") );
        }
        // Type, sub-type and status are pattern lists like the rest now, so "alarms and
        // warnings" is one call. No hashed column for any of them, so every entry is a pattern.
        String typeCondition = anyOf(null, FilterPatterns.allPatterns(filter.getType()), null, "type", "type", params);
        if(typeCondition != null){
            criterias.add( new SqlField("type", filter.getType(), typeCondition + " ") );
        }
        String subTypeCondition = anyOf(null, FilterPatterns.allPatterns(filter.getSubType()), null, "sub_type", "sub_type", params);
        if(subTypeCondition != null){
            criterias.add( new SqlField("sub_type", filter.getSubType(), subTypeCondition + " ") );
        }
        String statusCondition = anyOf(null, FilterPatterns.allPatterns(filter.getStatus()), null, "status", "status", params);
        if(statusCondition != null){
            criterias.add( new SqlField("status", filter.getStatus(), statusCondition + " ") );
        }
        String dataSetCondition = dataSetIdCondition(filter.getDataSetId(), "data_set_id", params);
        if(dataSetCondition != null){
            criterias.add( new SqlField("data_set_id", filter.getDataSetId(), dataSetCondition + " ") );
        }

        if(filter.getCreatedTime() != null){
            TimeFilter timeFilter = filter.getCreatedTime();
            if(timeFilter.getMin() != null){
                ZonedDateTime dateCreated = timeFilter.getMin();
                criterias.add( new SqlField("date_created", dateCreated, "date_created >= {dateCreated:DateTime64(3)} ") );
                params.put("dateCreated", toChDateTime(dateCreated));
            }

            if(timeFilter.getMax() != null){
                ZonedDateTime createdTimeMax = timeFilter.getMax();
                criterias.add( new SqlField("date_created", createdTimeMax, "date_created <= {createdTimeMax:DateTime64(3)} ") );
                params.put("createdTimeMax", toChDateTime(createdTimeMax));
            }
        }

        if(filter.getLastUpdatedTime() != null){
            TimeFilter timeFilter = filter.getLastUpdatedTime();
            if(timeFilter.getMin() != null){
                ZonedDateTime lastUpdated = timeFilter.getMin();
                criterias.add( new SqlField("last_updated", lastUpdated, "last_updated >= {lastUpdated:DateTime64(3)} ") );
                params.put("lastUpdated", toChDateTime(lastUpdated));
            }

            if(timeFilter.getMax() != null){
                ZonedDateTime lastUpdatedMax = timeFilter.getMax();
                criterias.add( new SqlField("last_updated", lastUpdatedMax, "last_updated <= {lastUpdatedMax:DateTime64(3)} ") );
                params.put("lastUpdatedMax", toChDateTime(lastUpdatedMax));
            }
        }

        if(filter.getEventTime() != null){
            TimeFilter timeFilter = filter.getEventTime();
            if(timeFilter.getMin() != null){
                ZonedDateTime startTime = timeFilter.getMin();
                criterias.add( new SqlField("event_time", startTime, "event_time >= {startTime:DateTime64(3)} ") );
                params.put("startTime", toChDateTime(startTime));
            }

            if(timeFilter.getMax() != null){
                ZonedDateTime startTimeMax = timeFilter.getMax();
                criterias.add( new SqlField("event_time", startTimeMax, "event_time < {startTimeMax:DateTime64(3)} ") );
                params.put("startTimeMax", toChDateTime(startTimeMax));
            }
        }
        return true;
    }

    public List<EventModel> filter(EventRetreiver retreiver) {
        return filter(retreiver, null);
    }

    /**
     * Dataset-ACL-aware variant: when {@code allowedDataSetIds != null} the result is additionally
     * constrained to {@code data_set_id IN (...)} on top of the user-supplied filter.
     */
    public List<EventModel> filter(EventRetreiver retreiver, Collection<Long> allowedDataSetIds) {
        // Before the try below, deliberately: its catch-all rewraps everything as "Failed to
        // retrieve events", so a rejection raised inside it reaches the caller as a 500. A cursor
        // this method cannot read is the caller's mistake and has to stay one.
        //
        // EventService rejects it earlier, with the request's own sort in hand. This guards the
        // direct call, where the alternative is NumberFormatException from a caller-supplied value.
        PageCursor supplied = PageCursor.decode(retreiver.getCursor());
        if (supplied != null && !canReadBoundary(resolveSort(retreiver.getSort()), supplied.value())) {
            throw new MalformedCursorException("The cursor's position cannot be read as a "
                    + resolveSort(retreiver.getSort()).property() + ".");
        }

        AtomicReference<List<EventModel>> results = new AtomicReference<>(new ArrayList<>());

        Client client = getClickhouseClient();
        try {

            EventFilter filter = retreiver.getFilter();

            List<SqlField> criterias = new ArrayList<>();
            Map<String, Object> params = new HashMap<>();

            String query = "SELECT %s FROM events e ".formatted(String.join(", ", EVENT_COLUMNS));

            if(!collectFilterCriteria(filter, params, criterias)){
                return results.get();
            }

            // Keyset pagination. Written as a plain range predicate AND a tie-breaker rather than
            // as one OR over both columns, and the difference is not cosmetic: `event_time >= x` on
            // its own is what lets ClickHouse prune whole monthly partitions and use the event_time
            // minmax index, and folding it into a single OR hides that from the planner, turning
            // every page into a full scan. The second clause then drops the rows of the boundary
            // millisecond that the previous page already returned.
            EventSortSpec sortSpec = resolveSort(retreiver.getSort());
            PageCursor after = PageCursor.decode(retreiver.getCursor());
            if(after != null){
                String col = sortSpec.column();
                String cmp = sortSpec.descending() ? "<" : ">";
                String type = cursorParamType(col);
                boolean nullsLast = !sortSpec.descending();
                params.put("afterId", UUID.fromString(after.id()));

                if(after.value() == null){
                    // The previous page ended inside the null block. What remains is the rest of
                    // that block — plus the whole not-null block when nulls come first.
                    String within = "(" + col + " IS NULL AND id " + cmp + " {afterId:UUID}) ";
                    criterias.add( new SqlField(col, after.id(),
                            nullsLast ? within : "(" + within + " OR " + col + " IS NOT NULL) ") );
                } else {
                    params.put("afterValue", cursorParamValue(col, after.value()));
                    String beyond = "(" + col + " " + cmp + " {afterValue:" + type + "}"
                            + " OR (" + col + " = {afterValue:" + type + "} AND id " + cmp + " {afterId:UUID})) ";
                    if(NULLABLE_COLUMNS.contains(col)){
                        // Nulls last: the null block still lies ahead. Nulls first: it is behind us,
                        // and the IS NOT NULL guard keeps it there.
                        criterias.add( new SqlField(col, after.value(), nullsLast
                                ? "((" + col + " IS NOT NULL AND " + beyond + ") OR " + col + " IS NULL) "
                                : "(" + col + " IS NOT NULL AND " + beyond + ") ") );
                    } else {
                        // Two clauses rather than one OR, and the difference is not cosmetic: a bare
                        // `event_time >= x` is what lets ClickHouse prune whole monthly partitions
                        // and use the event_time minmax index. Folding it into a single OR hides
                        // that from the planner and turns every page into a full scan.
                        criterias.add( new SqlField(col, after.value(),
                                col + " " + cmp + "= {afterValue:" + type + "} ") );
                        criterias.add( new SqlField(col, after.id(), beyond) );
                    }
                }
            }

            AdvancedFilter advancedFilter = retreiver.getAdvancedFilter();
            if(advancedFilter != null){
                buildAdvancedFilter(criterias, params, advancedFilter, null);
            }

            StringBuilder strBuilder = new StringBuilder();
            String STRJOIN = "WHERE";
            boolean hasWhere = false;
            for (SqlField criteria : criterias) {
                if (criteria.sqlOperation() != null) {
                    var op = criteria.sqlOperation();
                    if (op.equals(SQLOperation.START_LIST)) {
                        // If it is an OR critera, do not start with AND
                        if (!criteria.sql().equals(" OR (")) {
                            strBuilder.append(" ").append(STRJOIN).append(" ");
                        } else {
                            // Handle case where no where has not been added
                            if (!hasWhere) {
                                strBuilder.append("WHERE (");
                                STRJOIN = "";
                                hasWhere = true;
                                continue;
                            }
                        }
                        STRJOIN = "";
                    } else if (op.equals(SQLOperation.END_LIST)) {
                        STRJOIN = "AND";
                    } else if (op.equals(SQLOperation.AND_LIST)) {
                        strBuilder.append(" ").append(STRJOIN).append(" ");
                        STRJOIN = "AND";
                    } else if (op.equals(SQLOperation.OR_LIST)) {
                        strBuilder.append(" ").append(STRJOIN).append(" ");
                        STRJOIN = "OR";
                    }
                    strBuilder.append(criteria.sql());

                } else {
                    strBuilder.append(" ").append(STRJOIN).append(" ");
                    strBuilder.append(criteria.sql());
                    STRJOIN = "AND";
                }
                hasWhere = true;
            }

            int limit = retreiver.getLimit();

            query += strBuilder;
            // AND the caller's dataset ACL on top of the user filter (read-all → allowed == null).
            // Bound as an Array(Int64) param, so no caller-derived value reaches the SQL string.
            String aclCondition = datasetAclCondition(allowedDataSetIds, "data_set_id", params);
            if (aclCondition != null) {
                query += (hasWhere ? " AND " : " WHERE ") + aclCondition;
            }
            query += orderByClause(retreiver);
            query += " LIMIT " + limit;
            log.debug("ClickHouse Event Query: {}", query);
            log.debug("{}", params);

            try (QueryResponse response = client.query(query, params).get(30, TimeUnit.SECONDS)) {
                var reader = client.newBinaryFormatReader(response);
                while(reader.hasNext()) {
                    var row = reader.next(); // Read the next record from stream and parse it
                    var em = createEventModel(row, reader);
                    results.get().add(em);
                }
                log.debug("Server time: {} ms, Query ID: {}", response.getServerTime(), response.getQueryId());
            } catch (Exception e) {
                log.error("Failed to fetch events from ClickHouse", e);
                throw new RuntimeException(e);
            }

        } catch (Exception e){
            // Do not swallow: a malformed query or a ClickHouse outage must surface as a 5xx, not a
            // silent empty 200 that looks like "no events matched".
            log.error("Failed to retrieve events from ClickHouse", e);
            throw new RuntimeException("Failed to retrieve events from ClickHouse", e);
        }

        return results.get();
    }

    /**
     * The {@code ORDER BY} for a filter query.
     *
     * <p>Defaults to {@code (event_time, id)} — the order the keyset below already pages in.
     * This path used to emit no {@code ORDER BY} at all, which with a {@code limit} means the rows
     * you get back are an arbitrary subset and two identical requests may disagree about which:
     * a wrong answer that reads as data shifting underneath you rather than as a missing clause.
     * What matters is that the order is <em>defined</em>, so the cheapest defined one wins.
     *
     * <p>Cheapest, specifically, is why this is not {@code date_created} even though the three node
     * filters sort newest-created-first. {@code events} is partitioned by
     * {@code toYYYYMM(event_time)}, so an {@code event_time} order runs with the table's physical
     * layout; {@code date_created} is a column the layout says nothing about, and ordering by it
     * would sort every matched row on every call. {@code id} is the tiebreaker rather than the sort
     * key on its own: it is unique, so it makes the order total, but a UUID order means nothing to
     * a caller.
     *
     * <p>Matching the keyset is the other half. A cursor encodes a position in one specific order,
     * so a default that disagreed with it would silently change the ordering the moment a caller
     * started paging — page one newest-first, page two oldest-first, with rows apparently missing
     * from both. They are the same order now, which is what makes paging coherent.
     *
     * <p>A keyset still overrides an explicit {@code sort} outright rather than being combined with
     * it. The cursor's position is only meaningful against the order it was produced in, so
     * honouring a different {@code sort} beside it would return a page that skips rows instead of
     * failing — a wrong answer, where ignoring the sort is merely a surprising one.
     *
     * <p>Sort properties are mapped through a fixed table, never interpolated: {@code sort} arrives
     * from the request body, and a column name pasted into SQL is an injection point no amount of
     * parameter binding elsewhere makes up for. An unknown property is dropped rather than rejected,
     * matching how the rest of this filter treats things it does not recognise.
     */
    private static String orderByClause(EventRetreiver retreiver) {
        EventSortSpec spec = resolveSort(retreiver.getSort());
        String direction = spec.descending() ? " DESC" : "";
        // Nulls last ascending, first descending — stated rather than left to the default, because
        // the keyset predicate assumes exactly this placement. If the two disagree, a page boundary
        // lands in the wrong block and rows vanish from the walk.
        String nulls = NULLABLE_COLUMNS.contains(spec.column())
                ? (spec.descending() ? " NULLS FIRST" : " NULLS LAST")
                : "";
        // id last, always: the sort column alone is not a position unless it is unique, and a page
        // boundary falling inside a run of equal values repeats or drops exactly those rows.
        return " ORDER BY " + spec.column() + direction + nulls + ", id" + direction;
    }


    /**
     * Event properties a caller may sort by, mapped to their columns. Deliberately a short list of
     * the indexed and low-cardinality ones — an ORDER BY over a column with no index sorts the whole
     * matched set, so opening this up to everything would let one request cost far more than it
     * looks like it should.
     */
    /**
     * Sortable columns that may be null. A keyset boundary on a nullable column silently drops
     * rows — NULL compares to nothing, so the page they belong in simply comes back short — so
     * these may be sorted by but not paged through. Sorting without a cursor is unaffected.
     */
    private static final Set<String> NULLABLE_COLUMNS = Set.of("sub_type", "status");

    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "eventTime", "event_time",
            "createdTime", "date_created",
            "lastUpdatedTime", "last_updated",
            "externalId", "external_id",
            "type", "type",
            "subType", "sub_type",
            "status", "status",
            "source", "source",
            "dataSetId", "data_set_id");

    /**
     * The order a request is served in: one sortable column plus the id tie-breaker.
     *
     * <p>One column rather than several, for now, because the cursor has to encode the position it
     * stopped at, and a multi-column position is a tuple comparison with a direction per column —
     * worth having, not worth guessing at. An unrecognised property falls back to the default
     * rather than failing, matching how the rest of this filter treats what it does not recognise.
     */
    public static EventSortSpec resolveSort(DataSort sort) {
        if (sort == null || sort.getProperty() == null || sort.getProperty().isEmpty()) {
            return EventSortSpec.DEFAULT;
        }
        for (String property : sort.getProperty()) {
            String column = SORTABLE_COLUMNS.get(property);
            if (column != null) {
                // Anything that is not an explicit "desc" is ascending, so a malformed order
                // degrades to the documented default instead of silently reversing the results.
                return new EventSortSpec(property, column, "desc".equalsIgnoreCase(sort.getOrder()));
            }
            log.debug("Ignoring unsortable event property '{}'", property);
        }
        return EventSortSpec.DEFAULT;
    }

    /** A resolved sort: the caller's property name, the column behind it, and the direction. */
    public record EventSortSpec(String property, String column, boolean descending) {
        /** Event time ascending — the order the keyset pages in, so paging does not change it. */
        public static final EventSortSpec DEFAULT = new EventSortSpec("eventTime", "event_time", false);
    }

    /**
     * Whether a cursor's boundary value can be read as the sorted column's type.
     *
     * <p>Cursors are opaque but unsigned, so anything can arrive in one. An unparseable boundary
     * used to throw a {@code NumberFormatException} out of the query builder — a 500 produced by a
     * value the caller supplied. Treated as a malformed cursor instead.
     */
    public static boolean canReadBoundary(EventSortSpec sort, String value) {
        if (value == null) {
            return true;
        }
        return switch (sort.column()) {
            case "event_time", "date_created", "last_updated", "data_set_id" -> isLong(value);
            default -> true;
        };
    }

    private static boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Whether a sort on this property can be paged.
     *
     * <p>Every sortable property can, now that the null block is handled. It used to exclude the
     * nullable columns, and the exclusion was worse than the problem: no cursor was produced for
     * them, so a walk sorted by subType returned its first page and stopped, and a client reading
     * "no nextCursor" as "no more rows" silently lost the rest.
     */
    public static boolean supportsCursor(String property) {
        return SORTABLE_COLUMNS.containsKey(property);
    }

    /**
     * The ClickHouse parameter type for a keyset boundary on this column, so the value binds as
     * what the column actually is rather than as text that happens to compare correctly.
     */
    private static String cursorParamType(String column) {
        return switch (column) {
            case "event_time", "date_created", "last_updated" -> "DateTime64(3)";
            case "data_set_id" -> "Int64";
            default -> "String";
        };
    }

    /** A cursor's boundary value, converted to what its column expects. */
    private static Object cursorParamValue(String column, String value) {
        return switch (column) {
            case "event_time", "date_created", "last_updated" ->
                    toChDateTime(Instant.ofEpochMilli(Long.parseLong(value)).atZone(ZoneOffset.UTC));
            case "data_set_id" -> Long.parseLong(value);
            default -> value;
        };
    }

    /**
     * Aggregate matching events into {@code (value, count)} buckets grouped by one field, instead
     * of returning the events themselves — so a caller can ask "how many, broken down by X" with a
     * tiny response. Honours the same MCP-exposed filters as the drill-down path (see
     * {@link #buildMcpEventWhere}) and the dataset ACL.
     *
     * <p>{@code groupBy} is a logical name, mapped here to a safe column/expression (never raw
     * caller input in the SQL): {@code type}, {@code subType}, {@code source}, {@code dataSetId},
     * or the time buckets {@code day}/{@code hour} over {@code event_time}.
     */
    public List<EventQueryResult.Bucket> aggregate(EventRetreiver retreiver, String groupBy,
                                                   Collection<Long> allowedDataSetIds) {
        String groupExpr = switch (groupBy) {
            case "type" -> "type";
            case "subType" -> "ifNull(sub_type, '(none)')";
            case "source" -> "source";
            case "dataSetId" -> "toString(data_set_id)";
            // Events sharing an external_id are the lifecycle of one logical event
            // (e.g. created -> approved -> paid); grouping by it counts transitions per event.
            case "externalId" -> "external_id";
            case "day" -> "formatDateTime(toStartOfDay(event_time), '%Y-%m-%d')";
            case "hour" -> "formatDateTime(toStartOfHour(event_time), '%Y-%m-%dT%H:00')";
            default -> throw new IllegalArgumentException(
                    "Unsupported groupBy '" + groupBy
                    + "'. Allowed: type, subType, source, dataSetId, externalId, day, hour");
        };

        Map<String, Object> params = new HashMap<>();
        String where = buildMcpEventWhere(retreiver.getFilter(), params, allowedDataSetIds);
        String sql = "SELECT " + groupExpr + " AS value, count() AS cnt FROM events"
                + where
                + " GROUP BY value ORDER BY cnt DESC, value ASC LIMIT 1000";

        log.debug("ClickHouse Event Aggregate Query: {}", sql);
        List<EventQueryResult.Bucket> buckets = new ArrayList<>();
        Client client = getClickhouseClient();
        try {
            Collection<GenericRecord> records = client.queryAll(sql, params);
            for (GenericRecord r : records) {
                buckets.add(new EventQueryResult.Bucket(r.getString("value"), r.getLong("cnt")));
            }
        } catch (Exception e) {
            log.error("Failed to aggregate events in ClickHouse", e);
            throw new RuntimeException("ClickHouse event aggregation failed", e);
        }
        return buckets;
    }

    /**
     * Builds the {@code WHERE} clause for the subset of filters the {@code event_filter} MCP tool
     * exposes ({@code externalId}, {@code source}, {@code type}, {@code subType},
     * {@code dataSetId}, {@code relatedResources}, {@code eventTime} range), plus the dataset
     * ACL. All caller values are
     * bound as ClickHouse named params — never concatenated into the SQL string. Shared by
     * {@link #aggregate} so aggregate and drill-down apply identical predicates.
     */
    /**
     * A parenthesised OR over the literal entries (matched on the hashed column, which is indexed)
     * and the wildcard entries (matched with ILIKE on the text column). Null when nothing was
     * supplied, so the caller can leave the predicate out entirely.
     *
     * <p>Every value is bound as a named parameter — nothing here is concatenated into the SQL.
     *
     * @param hashColumn the indexed hash column, or null when the field has none and every entry
     *                   is therefore a pattern
     */
    /**
     * The literal {@code externalId} entries, hashed the way every event writer hashes them:
     * BLAKE3 over {@code externalId + tenantId}, 128 bits, salted per tenant.
     *
     * <p>Computed here rather than on the filter because the tenant is a property of the request,
     * not of the wire DTO. Reaching for {@code ExternalIds.hash} instead — the node hash — produces
     * a 64-bit unsalted xxHash3 that cannot equal anything in this column, so an exact external-id
     * filter silently returned nothing while looking perfectly well-formed.
     *
     * <p>Note this is case-<em>sensitive</em>, unlike the wildcard branch beside it, because the
     * writers hash the external id verbatim. Nodes lowercase before hashing; events never have.
     */
    private List<BigInteger> externalIdHashes(EventFilter filter) {
        String tenant = getDatabaseName();
        return filter.getExactExternalIds().stream()
                .map(externalId -> IdGenerator.generate128bitKeySigned(externalId, tenant))
                .toList();
    }

    private static String anyOf(Collection<BigInteger> exactHashes, Collection<String> patterns,
                                String hashColumn, String textColumn, String paramPrefix,
                                Map<String, Object> params) {
        List<String> alternatives = new ArrayList<>();
        if (hashColumn != null && exactHashes != null && !exactHashes.isEmpty()) {
            // Int128, because that is what the column is. The keys are unsigned 128-bit values, so
            // half of them are stored as their negative two's-complement twin; binding UInt128 here
            // would miss exactly those, silently. This started as Array(Int64) holding the 64-bit
            // node hash, which matched nothing at all.
            alternatives.add(hashColumn + " IN {" + paramPrefix + "_hashes:Array(Int128)}");
            params.put(paramPrefix + "_hashes", exactHashes);
        }
        int i = 0;
        for (String pattern : patterns) {
            String name = paramPrefix + "_p" + i++;
            alternatives.add(textColumn + " ILIKE {" + name + ":String}");
            params.put(name, pattern);
        }
        if (alternatives.isEmpty()) {
            return null;
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    private String buildMcpEventWhere(EventFilter filter, Map<String, Object> params,
                                      Collection<Long> allowedDataSetIds) {
        List<String> c = new ArrayList<>();
        String externalIdCondition = anyOf(externalIdHashes(filter), filter.getExternalIdPatterns(),
                "external_id_hash", "external_id", "ext", params);
        if (externalIdCondition != null) {
            c.add(externalIdCondition);
        }
        String sourceCondition = anyOf(null, filter.getSourcePatterns(), null, "source", "src", params);
        if (sourceCondition != null) {
            c.add(sourceCondition);
        }
        String typeCondition = anyOf(null, FilterPatterns.allPatterns(filter.getType()), null, "type", "type", params);
        if (typeCondition != null) {
            c.add(typeCondition);
        }
        String subTypeCondition = anyOf(null, FilterPatterns.allPatterns(filter.getSubType()), null, "sub_type", "sub_type", params);
        if (subTypeCondition != null) {
            c.add(subTypeCondition);
        }
        String statusCondition = anyOf(null, FilterPatterns.allPatterns(filter.getStatus()), null, "status", "status", params);
        if (statusCondition != null) {
            c.add(statusCondition);
        }
        String dataSetCondition = dataSetIdCondition(filter.getDataSetId(), "data_set_id", params);
        if (dataSetCondition != null) {
            c.add(dataSetCondition);
        }
        if (filter.getEventTime() != null) {
            TimeFilter tf = filter.getEventTime();
            if (tf.getMin() != null) {
                c.add("event_time >= {evStart:DateTime64(3)}");
                params.put("evStart", toChDateTime(tf.getMin()));
            }
            if (tf.getMax() != null) {
                c.add("event_time < {evEnd:DateTime64(3)}");
                params.put("evEnd", toChDateTime(tf.getMax()));
            }
        }
        if (filter.getRelatedResources() != null && !filter.getRelatedResources().isEmpty()) {
            // Same hasAll semantics as the REST filter path: the event must relate to ALL of the
            // given resources. Matched against the derived id/hash columns so the bloom filters apply.
            Set<Long> ids = new HashSet<>();
            Set<Long> externalIdHashes = new HashSet<>();
            filter.getRelatedResources().forEach(it -> {
                if (it.getId() != null) {
                    ids.add(it.getId());
                } else if (it.getExternalId() != null) {
                    externalIdHashes.add(ExternalIds.hash(it.getExternalId()));
                }
            });
            if (!ids.isEmpty()) {
                c.add("hasAll(related_resources_id, {rel_ids:Array(Int64)})");
                params.put("rel_ids", ids);
            }
            if (!externalIdHashes.isEmpty()) {
                c.add("hasAll(related_resources_external_id_hash, {rel_ext_hashes:Array(Int64)})");
                params.put("rel_ext_hashes", externalIdHashes);
            }
        }
        String acl = datasetAclCondition(allowedDataSetIds, "data_set_id", params);
        if (acl != null) {
            c.add(acl);
        }
        return c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
    }

    /**
     * Full-text-ish search across events. The input string is matched case-insensitively
     * against {@code external_id}, {@code description}, and {@code mapValues(metadata)}
     * using {@code ILIKE '%q%'}. The ngrambf_v1 index on {@code external_id} accelerates
     * the external-id branch; {@code description} is scanned, which is acceptable for
     * the small per-tenant event volumes this surface is aimed at. For heavier full-text
     * needs, add a tokenbf_v1 index on {@code description} and/or use {@code hasToken}.
     *
     * <p>An empty or null query returns the most recent events up to {@code limit}.
     */
    public List<EventModel> search(String query, int limit) {
        return search(query, limit, null);
    }

    /**
     * Dataset-ACL-aware variant: when {@code allowedDataSetIds != null} the search is constrained
     * to {@code data_set_id IN (...)}. The text-match OR group is parenthesised so the ACL ANDs
     * correctly on top of it.
     */
    public List<EventModel> search(String query, int limit, Collection<Long> allowedDataSetIds) {
        return search(query, limit, allowedDataSetIds, null);
    }

    /**
     * The same, additionally narrowed by an {@link EventFilter}.
     *
     * <p>The phrase decides which events are candidates; the filter only removes some of them. Its
     * criteria are built by {@link #collectFilterCriteria}, the same method {@link #filter} uses, so
     * a filter means field for field what it means on {@code POST /events/filter} — it is ANDed with
     * the text match and the dataset ACL alike. A null filter narrows nothing.
     */
    public List<EventModel> search(String query, int limit, Collection<Long> allowedDataSetIds,
                                   EventFilter filter) {
        List<EventModel> results = new ArrayList<>();
        Client client = getClickhouseClient();
        try {
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                params.put("q", "%" + query + "%");
                conditions.add("(external_id ILIKE {q:String} "
                        + "OR description ILIKE {q:String} "
                        + "OR arrayExists(v -> v ILIKE {q:String}, mapValues(metadata)))");
            }
            if (filter != null) {
                List<SqlField> criterias = new ArrayList<>();
                if (!collectFilterCriteria(filter, params, criterias)) {
                    // The filter cannot match anything; don't ask ClickHouse.
                    return results;
                }
                criterias.forEach(criteria -> conditions.add(criteria.sql()));
            }
            String aclCondition = datasetAclCondition(allowedDataSetIds, "data_set_id", params);
            if (aclCondition != null) {
                conditions.add(aclCondition);
            }
            String whereClause = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions) + " ";
            String sql = "SELECT " + String.join(", ", EVENT_COLUMNS)
                    + " FROM events e " + whereClause
                    + " ORDER BY event_time DESC"
                    + " LIMIT " + Math.max(1, Math.min(limit, 1000));

            try (QueryResponse response = client.query(sql, params).get(30, TimeUnit.SECONDS)) {
                var reader = client.newBinaryFormatReader(response);
                while (reader.hasNext()) {
                    var row = reader.next();
                    results.add(createEventModel(row, reader));
                }
            } catch (Exception e) {
                log.error("Failed to search events in ClickHouse", e);
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            // Do not swallow: a malformed query or a ClickHouse outage must surface as a 5xx, not a
            // silent empty 200 that looks like "no events matched" (same policy as filter()).
            log.error("Failed to search events in ClickHouse", e);
            throw new RuntimeException("Failed to search events in ClickHouse", e);
        }
        return results;
    }

    // Package-private for direct unit testing of the generated placeholder syntax.
    void buildAdvancedFilter(
            List<SqlField> criterias,
            Map<String, Object> params,
            AdvancedFilter advancedFilter,
            SQLOperation andOr
    ) {
        if(advancedFilter.getOr() != null && !advancedFilter.getOr().isEmpty()){

            //
            criterias.add( new SqlField(null, null, " OR (", SQLOperation.START_LIST));
            for(AdvancedFilter f : advancedFilter.getOr()){
                buildAdvancedFilter(criterias, params, f, SQLOperation.OR_LIST);
            }
            criterias.add( new SqlField(null, null, ")", SQLOperation.END_LIST));

        } else if (advancedFilter.getAnd() != null && !advancedFilter.getAnd().isEmpty()){

            criterias.add( new SqlField(null, null, "(", SQLOperation.START_LIST));
            for(AdvancedFilter f : advancedFilter.getAnd()){
                buildAdvancedFilter(criterias, params, f, SQLOperation.AND_LIST);
            }
            criterias.add( new SqlField(null, null, ")", SQLOperation.END_LIST));

        } else if (advancedFilter.getNot() != null){
            var notFilter = advancedFilter.getNot();
            AdvancedFilterOperator advancedFilterOperator = notFilter.getFilterOperator();

            var propertyName = advancedFilterOperator.getProperty().getFirst();
            var snakeCasedProperty = TextValidator.toSnakeLowerCased(propertyName);

            Operator currentOperator = advancedFilterOperator.getOperator();
            var propertyWithId = propertyName + IdGenerator.getRandomId();
            // Pass andOr through (4-arg SqlField), like the plain leaf below: a `not` inside an
            // and/or list must join with that list's operator, not the WHERE-builder's default AND.
            if(currentOperator == Operator.in){
                // `in` carries its payload in `values`, bound as an array — mirroring the plain leaf.
                criterias.add( new SqlField(snakeCasedProperty, advancedFilterOperator.getValues(),
                        String.format("NOT e.%s IN {%s:Array(String)}", snakeCasedProperty, propertyWithId),
                        andOr));
                params.put(propertyWithId, advancedFilterOperator.getValues());
            } else {
                var propertyValue = advancedFilterOperator.getValue();
                if(currentOperator == Operator.prefix){
                    propertyValue += "%";
                }
                criterias.add( new SqlField(snakeCasedProperty, propertyValue,
                        String.format("NOT e.%s %s {%s:String}", snakeCasedProperty, currentOperator.getSymbol(), propertyWithId),
                        andOr));
                params.put(propertyWithId, propertyValue);
            }
        } else {
            AdvancedFilterOperator advancedFilterOperator = advancedFilter.getFilterOperator();
            var propertyName = advancedFilterOperator.getProperty().getFirst();
            var snakeCasedProperty = TextValidator.toSnakeLowerCased(propertyName);

            var propertyValue = advancedFilterOperator.getValue();

            Operator currentOperator = advancedFilterOperator.getOperator();
            var propertyWithId = propertyName + IdGenerator.getRandomId();
            String query = String.format("e.%s %s {%s:String}", snakeCasedProperty, currentOperator.getSymbol(), propertyWithId);
            if(currentOperator == Operator.prefix){
                propertyValue += "%";
                query = String.format("e.%s %s {%s:String}", snakeCasedProperty, currentOperator.getSymbol(), propertyWithId);
                criterias.add( new SqlField(snakeCasedProperty, propertyValue, query, andOr) );
                params.put(propertyWithId, propertyValue);
            }
            else if(currentOperator == Operator.in){
                query = String.format("e.%s IN {%s:Array(String)}", snakeCasedProperty, propertyWithId);
                criterias.add( new SqlField(snakeCasedProperty, propertyValue, query, andOr) );
                params.put(propertyWithId, advancedFilterOperator.getValues());
            } else {
                criterias.add( new SqlField(snakeCasedProperty, propertyValue, query, andOr) );
                params.put(propertyWithId, propertyValue);
            }

        }
    }

    public long count() {
        AtomicLong count = new AtomicLong();
        String query = "SELECT count(1) as count FROM events";

        Client client = getClickhouseClient();
        client.queryAll(query).forEach(r -> {
            count.set(r.getLong("count"));
        });

        return count.get();
    }

    /**
     * Authoritative DISTINCT (data_set_id, type) pairs for the current tenant, used by the weekly
     * reconciliation job to rebuild the Postgres {@code event_type_dim} table (which only ever gains
     * values on the write path, never retracts them). The column name is a fixed literal, not input.
     */
    public List<DatasetValue> distinctTypePairs() {
        return distinctPairs("type", false);
    }

    /** DISTINCT (data_set_id, sub_type) pairs, excluding rows where sub_type is NULL. */
    public List<DatasetValue> distinctSubTypePairs() {
        return distinctPairs("sub_type", true);
    }

    /** DISTINCT (data_set_id, status) pairs, excluding rows where status is NULL. */
    public List<DatasetValue> distinctStatusPairs() {
        return distinctPairs("status", true);
    }

    /** DISTINCT (data_set_id, source) pairs, excluding rows where source is NULL. */
    public List<DatasetValue> distinctSourcePairs() {
        return distinctPairs("source", true);
    }

    private List<DatasetValue> distinctPairs(String column, boolean nullable) {
        String query = "SELECT DISTINCT data_set_id, " + column + " FROM events"
                + (nullable ? " WHERE " + column + " IS NOT NULL" : "");
        List<DatasetValue> pairs = new ArrayList<>();
        Client client = getClickhouseClient();
        client.queryAll(query).forEach(r -> {
            String value = r.getString(column);
            if (value != null && !value.isBlank()) {
                pairs.add(new DatasetValue(r.getLong("data_set_id"), value));
            }
        });
        return pairs;
    }

    public DataWrapper<EventModel> findById(String id) {
        return findById(id, null);
    }

    /**
     * Dataset-ACL-aware variant: when {@code allowedDataSetIds != null} an event outside the
     * caller's readable datasets is treated as not found.
     */
    public DataWrapper<EventModel> findById(String id, Collection<Long> allowedDataSetIds) {
        DataWrapper<EventModel> data = new DataWrapper<>();

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        String aclCondition = datasetAclCondition(allowedDataSetIds, "data_set_id", params);
        String query = "SELECT %s FROM events e WHERE e.id = {id:UUID}%s".formatted(
                String.join(",", EVENT_COLUMNS), aclCondition == null ? "" : " AND " + aclCondition);

        Client client = getClickhouseClient();
        client.queryAll(query, params).forEach(r -> {
            data.getItems().add(createEventModel(r));
        });

        if(data.getItems().isEmpty()){
            throw new ObjectNotFoundException("Event with id: %s not found".formatted(id));
        }
        return data;
    }
}
