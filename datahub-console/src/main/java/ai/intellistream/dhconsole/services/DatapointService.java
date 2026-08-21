// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.services;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.models.ExternalIdAndHours;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DatapointService {

    private final DatahubApi datahubApi;

    public DatapointService(DatahubApi datahubApi) {
        this.datahubApi = datahubApi;
    }

    public DataWrapper<DataCollection<?>> getDatapointsByHoursAgo(ExternalIdAndHours form){
        DataRetriever<RetrieveFilter> reqData = new DataRetriever<>();
        var filter = new RetrieveFilter();
        ZonedDateTime now = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("UTC"));

        if(form.getStartTime() != null){
            filter.setStart(form.getStartTime());
        } else if(form.getHoursAgo() != null){
            filter.setStart( now.minusHours(form.getHoursAgo()) );
        }

        if(form.getEndTime() != null) {
            filter.setEnd(form.getEndTime());
        }

        if(!form.getRaw()){
            filter.setAggregates(List.of("avg", "min", "max"));
            String granularity = form.getGranularity();
            if (granularity == null || granularity.isBlank()) {
                granularity = "1 min";
            }
            filter.setGranularity(granularity);
        }

        filter.setExternalId(form.getExternalId());
        filter.setLimit(100000);
        reqData.getItems().add(filter);
        return datahubApi.retrieveDatapoints(reqData);
    }

}

