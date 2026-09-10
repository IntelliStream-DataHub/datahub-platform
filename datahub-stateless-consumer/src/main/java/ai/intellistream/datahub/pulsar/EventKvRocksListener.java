// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.dto.UUIDAndBigIntHash;
import ai.intellistream.datahub.models.EventModel;
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
     *
     * <p>No UPDATE case: KVRocks only holds the externalId→UUIDs mapping, and an event's
     * externalId is immutable (absent from {@code EventFields}), so an update never changes
     * anything stored here. CREATE is written synchronously by the api ({@code saveEvents}),
     * which leaves DELETE as the one action with consumer-side KVRocks work.
     */
    public void process(EventCudMessage message) throws Exception {
        switch (message.getEventObject()){
            case EVENT -> {
                switch (message.getEventAction()){
                    case DELETE -> delete(message);
                }
            }
        }
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
