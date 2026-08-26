// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.timeseries.Timeseries;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class TimeseriesTransformer {

    public static Collection<Timeseries> from(Collection<TimeseriesEntity> tsCollection) {
        Collection<Timeseries> tsList = new ArrayList<>();
        for(TimeseriesEntity n : tsCollection){
            tsList.add( from(n) );
        }
        return tsList;
    }


    public static Timeseries from(TimeseriesEntity source) {
        Timeseries target = new Timeseries();
        target.setDescription(source.getDescription());
        target.setId(source.getId());


        if (source.getDataSet() != null) {
            target.setDataSetId(source.getDataSet().getId());
        }
        // Copy into a plain HashMap so the DTO doesn't alias Hibernate's PersistentMap — Jackson
        // serializes this DTO outside the transaction and would otherwise hit
        // LazyInitializationException on the metadata collection.
        Map<String, String> meta = source.getMetadata();
        target.setMetadata(meta == null ? new HashMap<>() : new HashMap<>(meta));
        target.setName(source.getName());
        target.setSource(source.getSource());

        if(source != null){
            target.setUnit(source.getUnit());
            target.setUnitExternalId(source.getUnitExternalId());
            TimeseriesValueType valueType = source.getValueType();
            if (valueType == null) {
                // A malformed row (null/dangling value_type_id) must not 500 the whole list.
                log.warn("Timeseries {} (id {}) has no value type; defaulting to BIGINT",
                        source.getExternalId(), source.getId());
                target.setValueType("BIGINT");
            } else {
                target.setValueType(TimeseriesValueType.getTableType(valueType).toUpperCase());
            }
            if(source.getTableEngine() != null){
                target.setTableEngine(source.getTableEngine().name());
            }
        }

        target.setCreatedTime(source.getDateCreated());
        target.setLastUpdatedTime(source.getLastUpdated());
        target.setExternalId(source.getExternalId());
        return target;
    }

    public static Timeseries from(TimeseriesEntity node, Collection<EdgeEntity> edgeEntities) {
        Timeseries ts = from(node);

        // Unified node-centric relations (both directions + direction + edge id) from the edges in hand.
        ts.setRelatedResources(
                RelatedNodeResolver.forNode(ts.getId(), EdgeProxyTransformer.fromEdgeEntities(edgeEntities)));

        return ts;
    }

    public static Collection<Timeseries> from(Collection<TimeseriesEntity> nodeEntities, Collection<EdgeEntity> edgeEntities) {
        Collection<Timeseries> tsList = new ArrayList<>();
        for(TimeseriesEntity n : nodeEntities){
            tsList.add( from(n, edgeEntities) );
        }
        return tsList;
    }
}
