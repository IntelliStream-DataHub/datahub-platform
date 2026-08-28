// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.dto.UUIDAndBigIntHash;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.validation.EventFields;
import ai.intellistream.datahub.services.KVRocksService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.*;

@Component
@Slf4j
public class EventKvRocksListener {

    public final KVRocksService kvRocksService;
    private final ClickHouseEventService clickHouseEventService;

    public EventKvRocksListener(KVRocksService kvRocksService, ClickHouseEventService clickHouseEventService){
        this.kvRocksService = kvRocksService;
        this.clickHouseEventService = clickHouseEventService;
    }

    /**
     * Apply one event-CUD message to KVRocks. Throws on failure so the {@link PulsarReceiveLoop}
     * retries it in place rather than acking a half-applied change.
     */
    public void process(EventCudMessage message) throws Exception {
        switch (message.getEventObject()){
            case EVENT -> {
                switch (message.getEventAction()){
                    case UPDATE -> updateExternalId(message);
                    case DELETE -> delete(message);
                }
            }
        }
    }

    private void updateExternalId(EventCudMessage message) throws Exception{
        // Keys that should be renamed from(key) -> to(value)
        Map<byte[], byte[]> renameKeys = new HashMap<>();

        // Go through each event found and map new values
        for(EventModel em : message.getEvents()){
            Optional<UpdateEventForm> foundUpdateForm =
                    message.getUpdateEvents().stream()
                            .filter( it -> it.getId().equals( UUID.fromString(em.getId()) ) )
                            .findFirst();
            if(foundUpdateForm.isPresent()){
                UpdateEventForm updateForm = foundUpdateForm.get();
                if(updateForm.getUpdate() != null){
                    EventFields fields = updateForm.getUpdate();
                    // Update externalId
                    if(fields.getExternalId().getSet() != null){
                        var t = message.getTenantId();
                        String newExternalId = fields.getExternalId().getSet();
                        log.debug("Rename key {} to {}", updateForm.getExternalId(), newExternalId);
                        var extIdBArr = externalIdToByteArray(updateForm.getExternalId(), t);
                        var newExtIdBArr = externalIdToByteArray(newExternalId, t);
                        renameKeys.put(extIdBArr, newExtIdBArr);
                    }
                }
            }
        }

        kvRocksService.updateKeys(renameKeys, message.getTenantId());
    }

    private byte[] externalIdToByteArray(String id, String tenant){
        return IdGenerator.generate128bitKey(id, tenant).toByteArray();
    }

    private void delete(EventCudMessage message) throws Exception{
        Set<byte[]> externalIdSet = new HashSet<>();
        Set<UUID> idSet = new HashSet<>();

        for(EventModel em : message.getEvents()){
            if(em.getId() != null){
                idSet.add( UUID.fromString(em.getId()));
            }
            if(em.getExternalId() != null){
                log.debug("Deleting key {} from kvrocks by external id: {}",
                        em.getId(),
                        em.getExternalId());

                var t = message.getTenantId();
                // 128-bit to match how events are stored (saveEvents) and looked up elsewhere;
                // a 256-bit key here would never match the stored keys and delete nothing.
                BigInteger externalIdHash = IdGenerator.generate128bitKey(em.getExternalId(), t);
                externalIdSet.add( externalIdHash.toByteArray() );
            }
        }

        if(externalIdSet.isEmpty() && idSet.isEmpty()) {
            log.debug("Id list is empty, no events will be deleted.");
        } else {
            kvRocksService.deleteKeys(externalIdSet, message.getTenantId());
        }

        // If there are ids, we need to find all related external Ids for it
        if(!idSet.isEmpty()){
            List<UUIDAndBigIntHash> results = clickHouseEventService.findAllById(idSet, message.getTenantId(), UUIDAndBigIntHash.class);
            for(UUIDAndBigIntHash r : results){
                log.debug("Deleting key {} from kvrocks", r.getId());
            }
            kvRocksService.deleteKeys(results, message.getTenantId());
        }
    }
}
