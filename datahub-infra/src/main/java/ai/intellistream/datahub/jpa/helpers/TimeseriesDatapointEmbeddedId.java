// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.helpers;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.ZonedDateTime;

@Getter
@Setter
@Embeddable
public class TimeseriesDatapointEmbeddedId implements Serializable {

    @ManyToOne
    @JoinColumn(name = "timeseries_id")
    private NodeEntity timeseries;

    @Column(name = "timestamp")
    private ZonedDateTime timestamp;

    public TimeseriesDatapointEmbeddedId(){

    }

    public TimeseriesDatapointEmbeddedId(NodeEntity timeseries, ZonedDateTime timestamp){
        this.timeseries = timeseries;
        this.timestamp = timestamp;
    }

}
