// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.timeseries;

import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collection;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TimeseriesCudMessage {

    private EventAction eventAction;
    private EventObject eventObject;
    private Collection<Timeseries> timeseriesList;
    private Collection<UpdateTimeseries> timeseriesUpdateList;

}
