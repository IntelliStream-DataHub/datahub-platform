// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class EventCudMessage {

    private EventAction eventAction;
    private EventObject eventObject;
    private List<EventModel> events = new ArrayList<>();
    private List<UpdateEventForm> updateEvents = new ArrayList<>();
    private String tenantId;

    public EventCudMessage(){
        this.tenantId = TenantContext.getTenantId();
    }

}
