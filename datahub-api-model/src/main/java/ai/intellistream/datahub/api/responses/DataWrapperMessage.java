// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Collection;

@Data
@ToString
@NoArgsConstructor
public class DataWrapperMessage{

    private EventAction eventAction;

    private EventObject eventObject;

    private Collection<DataCollectionString> items;

    private String tenantId;

    public DataWrapperMessage(
            EventObject eventObject,
            EventAction eventAction,
            Collection<DataCollectionString> items,
            String tenantId
    ) {
        this.eventObject = eventObject;
        this.eventAction = eventAction;
        this.setItems(items);
        this.tenantId = tenantId;
    }
}
