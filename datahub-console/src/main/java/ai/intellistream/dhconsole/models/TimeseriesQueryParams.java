// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.models;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class TimeseriesQueryParams {

    private long limit = 100L;

    private List<Long> assetIds;

    public TimeseriesQueryParams setLimit(Long limit) {
        this.limit = limit;
        return this;
    }

    public TimeseriesQueryParams setAssetIds(List<Long> assetIds) {
        this.assetIds = assetIds;
        return this;
    }
}
