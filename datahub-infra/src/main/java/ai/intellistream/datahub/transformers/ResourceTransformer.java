// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.Resource;
import org.neo4j.driver.types.Node;

import java.time.ZonedDateTime;
import java.util.*;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.getTableType;

public class ResourceTransformer {

    public static List<Resource> from(Collection<? extends NodeEntity> nodes) {
        return nodes.stream().map(ResourceTransformer::from).toList();
    }
    public static List<Resource> fromTimeseriesEntities(Collection<TimeseriesEntity> nodes) {
        return nodes.stream().map(ResourceTransformer::from).toList();
    }
    /**
     * Resource node is the data stored in Postgresql
     * @param node
     * @return
     */
    public static Resource from(NodeEntity node) {
        Resource resource = new Resource();
        resource.setId(node.getId());
        resource.setName(node.getName());
        resource.setDescription(node.getDescription());
        resource.setSource(node.getSource());
        resource.setCreatedTime(node.getDateCreated());
        resource.setLastUpdatedTime(node.getLastUpdated());
        if(node instanceof AssetEntity asset){
            if(asset.getGeoLocation() != null){
                resource.setGeoLocation(new GeoLocation(asset.getGeoLocation()));
            }
        }
        if(node instanceof TimeseriesEntity ts){

            resource.setValueType(getTableType(ts.getValueType()));

        }

        if(node.getDataSet() != null){
            resource.setDataSetId(node.getDataSet().getId());
        }
        resource.setExternalId(node.getExternalId());
        resource.setIsRoot(node.getIsRoot());
        if(node.getLabels() != null && !node.getLabels().isBlank()){
            String[] labels = node.getLabels().split(",");
            if(labels.length > 0){
                resource.setLabels(Arrays.stream(labels).toList());
            }
        }
        // Copy into a plain HashMap so the DTO doesn't hold a reference to Hibernate's
        // PersistentMap. Without the copy, Jackson iterates the lazy collection during JSON
        // serialization after the transaction has closed -> LazyInitializationException.
        Map<String, String> meta = node.getMetadata();
        resource.setMetadata(meta == null ? new HashMap<>() : new HashMap<>(meta));
        return resource;
    }

    /**
     * Node is the data stored in neo4j
     //* @param node
     * @return
     */
    public static Resource fromNode(Node node) {
        Resource resource = new Resource();
        var nodeMap = node.asMap();
        resource.setName((String)nodeMap.get("name"));
        resource.setDescription((String)nodeMap.get("description"));
        resource.setId((Long)nodeMap.get("id"));
        resource.setDataSetId((Long) nodeMap.get("dataSetId"));
        resource.setExternalId((String)nodeMap.get("externalId"));
        resource.setElementId(node.elementId());
        resource.setSource((String)nodeMap.get("source"));
        resource.setIsRoot((Boolean)nodeMap.get("isRoot"));
        var geoValue = node.get("geoLocation");
        if(geoValue != null && !geoValue.isNull()){
            // The graph stores only a native WGS-84 point; reconstruct a GeoJSON Point (lossy for
            // non-point geometries, which Postgres holds in full).
            var point = geoValue.asPoint();
            resource.setGeoLocation(new GeoLocation(
                    "{\"type\":\"Point\",\"coordinates\":[" + point.x() + "," + point.y() + "]}"));
        }
        try{
            long epochCreatedTime = (Long)nodeMap.get("createdTime");
            ZonedDateTime ct = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(epochCreatedTime);
            resource.setCreatedTime(ct);
        } catch (Exception e){
            resource.setCreatedTime((ZonedDateTime) nodeMap.get("createdTime"));
        }
        try{
            long epochLastUpdated = (Long)nodeMap.get("lastUpdatedTime");
            ZonedDateTime ct = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(epochLastUpdated);
            resource.setLastUpdatedTime(ct);
        } catch (Exception e){
            resource.setLastUpdatedTime((ZonedDateTime) nodeMap.get("createdTime"));
        }
        List<String> labels = new ArrayList<>();
        Iterator<String> labelsIter = node.labels().iterator();
        labelsIter.forEachRemaining(labels::add);
        resource.setLabels(labels);

        // Metadata rides on the node as "metadata_"-prefixed properties (GraphEventNeo4jListener),
        // the same convention EdgeProxyTransformer reads off relationships. It used to be dropped
        // here, so every graph-sourced response returned nodes with empty metadata.
        Map<String, String> metadata = new HashMap<>();
        nodeMap.forEach((key, value) -> {
            if (key.startsWith(EdgeProxyTransformer.METADATA_PREFIX) && value != null) {
                metadata.put(key.substring(EdgeProxyTransformer.METADATA_PREFIX.length()), String.valueOf(value));
            }
        });
        resource.setMetadata(metadata);
        return resource;
    }

    public static Resource from(NodeEntity nodeEntity, List<EdgeProxy> relationships) {
        Resource a = ResourceTransformer.from(nodeEntity);
        attachRelatedResources(a, relationships);
        return a;
    }

    public static Resource fromNode(Node node, List<EdgeProxy> relationships) {
        Resource resource = fromNode(node);
        attachRelatedResources(resource, relationships);
        return resource;
    }

    /** Populate the unified node-centric {@code relatedResources} from the edges touching this resource. */
    private static void attachRelatedResources(Resource resource, List<EdgeProxy> relationships) {
        resource.setRelatedResources(RelatedNodeResolver.forNode(resource.getId(), relationships));
    }

}
