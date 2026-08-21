// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.timeseries.enums.TableEngine;

public interface NameAndExternalIdAndType {

    Long getId();
    String getName();
    String getExternalId();
    Long getExternalIdHash();

    TimeseriesValueType getValueType();

    TableEngine getTableEngine();

}
