// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import lombok.Data;

import java.util.List;

/**
 * How a filtered listing is ordered: one property, and a direction.
 *
 * <p>There is deliberately no {@code nulls} option. Where nulls sit follows the direction —
 * {@code NodeSort.nullsLast()} puts them last ascending and first descending, and the ClickHouse
 * event path matches — because that is how a Postgres btree stores them by default, so a plain
 * {@code CREATE INDEX} on the column serves the ordering either way without a {@code NULLS} clause.
 * Letting a caller override it would need index definitions per placement, or a sort, and the
 * placement would have to travel inside the page cursor beside the property and direction or a walk
 * could flip it mid-page.
 *
 * <p>A {@code nulls} field did exist here, validated and lower-cased on the way in, and was read by
 * nothing in any commit in this repository's history. Callers sending it got a 200 and the default
 * placement. It now reaches them as an unknown-field 400, which is at least an honest answer.
 */
@Data
public class DataSort {

    private List<String> property;
    private String order;

}
