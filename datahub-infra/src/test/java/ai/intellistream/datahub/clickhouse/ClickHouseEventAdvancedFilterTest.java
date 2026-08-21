// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.datafilters.SqlField;
import ai.intellistream.datahub.models.events.AdvancedFilter;
import ai.intellistream.datahub.models.events.AdvancedFilterOperator;
import ai.intellistream.datahub.models.events.AdvancedNotFilter;
import ai.intellistream.datahub.models.events.Operator;
import ai.intellistream.datahub.models.events.SQLOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards that the advanced event filter emits ClickHouse v2 named-parameter placeholders
 * ({@code {name:Type}}) that {@code client.query(sql, params)} actually binds — not the {@code :name}
 * form, which the v2 client leaves untouched in the SQL, producing a syntax error.
 */
class ClickHouseEventAdvancedFilterTest {

    // buildAdvancedFilter never touches the client/tenant deps, so nulls are fine here.
    private final ClickHouseEventService service = new ClickHouseEventService(null, null, null);

    private static AdvancedFilter leaf(Operator op, String property, String value, List<String> values) {
        AdvancedFilterOperator operator = new AdvancedFilterOperator();
        operator.setOperator(op);
        operator.setProperty(List.of(property));
        operator.setValue(value);
        operator.setValues(values);
        AdvancedFilter filter = new AdvancedFilter();
        filter.setFilterOperator(operator);
        return filter;
    }

    @Test
    void equals_bindsBracedStringParam() {
        List<SqlField> criterias = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        service.buildAdvancedFilter(criterias, params, leaf(Operator.equals, "type", "alarm", null), null);

        assertEquals(1, criterias.size());
        assertEquals(1, params.size());
        String key = params.keySet().iterator().next();
        assertEquals("e.type = {" + key + ":String}", criterias.get(0).sql());
        assertEquals("alarm", params.get(key));
    }

    @Test
    void in_bindsBracedArrayParam() {
        List<SqlField> criterias = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        service.buildAdvancedFilter(criterias, params, leaf(Operator.in, "status", null, List.of("open", "closed")), null);

        assertEquals(1, criterias.size());
        assertEquals(1, params.size());
        String key = params.keySet().iterator().next();
        assertEquals("e.status IN {" + key + ":Array(String)}", criterias.get(0).sql());
        assertEquals(List.of("open", "closed"), params.get(key));
    }

    private static AdvancedFilter not(Operator op, String property, String value, List<String> values) {
        AdvancedNotFilter inner = new AdvancedNotFilter(null, null, null, null, null, null, null);
        AdvancedFilterOperator operator = new AdvancedFilterOperator();
        operator.setOperator(op);
        operator.setProperty(List.of(property));
        operator.setValue(value);
        operator.setValues(values);
        inner.setFilterOperator(operator);
        AdvancedFilter filter = new AdvancedFilter();
        filter.setNot(inner);
        return filter;
    }

    @Test
    void notEquals_bindsBracedStringParam() {
        List<SqlField> criterias = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        service.buildAdvancedFilter(criterias, params, not(Operator.equals, "type", "alarm", null), null);

        assertEquals(1, criterias.size());
        String key = params.keySet().iterator().next();
        assertEquals("NOT e.type = {" + key + ":String}", criterias.get(0).sql());
        assertEquals("alarm", params.get(key));
    }

    @Test
    void notIn_bindsBracedArrayParamFromValues() {
        // `in` carries its payload in `values`; binding the (null) scalar `value` instead used to
        // produce a ClickHouse binding error under NOT.
        List<SqlField> criterias = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        service.buildAdvancedFilter(criterias, params, not(Operator.in, "status", null, List.of("open", "closed")), null);

        assertEquals(1, criterias.size());
        String key = params.keySet().iterator().next();
        assertEquals("NOT e.status IN {" + key + ":Array(String)}", criterias.get(0).sql());
        assertEquals(List.of("open", "closed"), params.get(key));
    }

    @Test
    void notInsideAnOrList_keepsTheOrJoin() {
        // A `not` leaf inside an or:[...] list must carry the OR_LIST operation so the WHERE
        // builder joins it with OR — dropping it silently narrowed or-filters to AND.
        AdvancedFilter orFilter = new AdvancedFilter();
        orFilter.setOr(List.of(
                leaf(Operator.equals, "type", "alarm", null),
                not(Operator.equals, "status", "closed", null)));

        List<SqlField> criterias = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        service.buildAdvancedFilter(criterias, params, orFilter, null);

        // START_LIST, leaf(OR_LIST), not-leaf(OR_LIST), END_LIST
        assertEquals(4, criterias.size());
        assertEquals(SQLOperation.OR_LIST, criterias.get(1).sqlOperation(), "plain leaf keeps OR");
        assertEquals(SQLOperation.OR_LIST, criterias.get(2).sqlOperation(), "not leaf must keep OR too");
    }
}
