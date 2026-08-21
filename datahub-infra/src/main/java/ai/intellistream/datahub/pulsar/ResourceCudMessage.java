// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ResourceCudMessage {

    private EventAction eventAction;
    private EventObject eventObject;
    private List<Resource> resources = new ArrayList<>();

    private List<EdgeProxy> edges = new ArrayList<>();
    private List<UpdateResourceForm> updateResourceForms = new ArrayList<>();
    private List<UpdateRelForm> updateEdges = new ArrayList<>();
    private List<UpdateTimeseries> updateTimeseries = new ArrayList<>();

    private NodeChangeMessage changeMessage;
    private String tenantId;

    public ResourceCudMessage(EventAction eventAction, EventObject eventObject, String tenantId){
        this.eventAction = eventAction;
        this.eventObject = eventObject;
        this.tenantId = tenantId;
    }

    public void setResources(Collection<NodeEntity> tsEntities) {
        setResources(ResourceTransformer.from(tsEntities));
    }

    public void setResources(List<Resource> resources){
        this.resources = resources;
    }

    public void setEdges(Collection<EdgeEntity> edgeEntities) {
        setEdges(EdgeProxyTransformer.fromEdgeEntities(edgeEntities));
    }

    public void setEdges(List<EdgeProxy> edges){
        this.edges = edges;
    }
}
