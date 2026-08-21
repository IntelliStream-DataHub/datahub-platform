// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.forms.RetrieveFilter;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@AllArgsConstructor
public class ClickHouseFilterData {

    private String filterQuery;
    private String query;
    private int paramsLength;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private RetrieveFilter f;

}
