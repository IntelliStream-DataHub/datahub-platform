// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;

import java.util.*;


public class ResourceTransformer {

    public static List<Resource> from(Collection<? extends NodeEntity> nodes) {
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

    public static Resource from(NodeEntity nodeEntity, List<EdgeProxy> relationships) {
        Resource a = ResourceTransformer.from(nodeEntity);
        attachRelatedResources(a, relationships);
        return a;
    }

    /** Populate the unified node-centric {@code relatedResources} from the edges touching this resource. */
    private static void attachRelatedResources(Resource resource, List<EdgeProxy> relationships) {
        resource.setRelatedResources(RelatedNodeResolver.forNode(resource.getId(), relationships));
    }

}
