// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.clickhouse.filter.EventFilterRenderer;
import ai.intellistream.datahub.filter.EventFilterParser;
import ai.intellistream.datahub.models.datafilters.SqlField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ASSEMBLED where clause, which fragment-level tests cannot see.
 *
 * <p>The property under test is one sentence: <b>the dataset ACL binds more tightly than anything
 * the caller supplied.</b> In SQL that is a question about parentheses rather than about the order
 * the strings were appended in — {@code AND} binds tighter than {@code OR}, so an ACL appended
 * after an unparenthesised top-level {@code OR} only constrains the second disjunct, and every row
 * matching the first is returned with no dataset restriction at all.
 */
class ClickHouseEventWhereClauseTest {

    private static final String ACL = "data_set_id IN {aclDs:Array(Int64)}";

    // renderWhere never touches the client/tenant deps, so nulls are fine here.
    private final ClickHouseEventService service = new ClickHouseEventService(null, null, null);

    /** The advanced filter as it now reaches the WHERE builder: one rendered, parenthesised term. */
    private static SqlField advanced(String expression) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = new EventFilterRenderer(params, expression)
                .render(EventFilterParser.parse(expression));
        return new SqlField("advancedFilter", null, sql);
    }

    /** A basic filter criterion, the shape collectFilterCriteria produces. */
    private static SqlField basic() {
        return new SqlField("type", "alarm", "type = {t:String} ");
    }

    /**
     * The ACL must be a top-level conjunct: everything the caller supplied has to sit inside one
     * balanced group that closes before the {@code AND} introducing the ACL. Asserted structurally
     * rather than by string equality, because the parameter names are random per call.
     */
    private static void assertAclIsTopLevelConjunct(String clause) {
        String suffix = " AND " + ACL;
        assertTrue(clause.endsWith(suffix), () -> "ACL is not the final conjunct: " + clause);

        String userPart = clause.substring(0, clause.length() - suffix.length()).trim();
        assertTrue(userPart.startsWith("WHERE "), () -> "expected a WHERE clause, got: " + clause);
        String body = userPart.substring("WHERE ".length()).trim();

        assertTrue(body.startsWith("(") && body.endsWith(")"),
                () -> "the caller's filter must be wrapped in its own group, or AND/OR precedence "
                        + "lets it escape the ACL. Got: " + clause);

        // The opening paren must match the FINAL character, not some inner group.
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '(') depth++;
            else if (body.charAt(i) == ')') depth--;
            if (depth == 0 && i < body.length() - 1) {
                throw new AssertionError("the caller's filter is not one balanced group, so the "
                        + "ACL sits at the precedence of its top-level operator. Got: " + clause);
            }
        }
        assertEquals(0, depth, () -> "unbalanced parentheses: " + clause);
    }

    @Test
    void topLevelOr_cannotEscapeTheDatasetAcl() {
        List<SqlField> criterias = new ArrayList<>();
        criterias.add(basic());
        criterias.add(advanced("source = 'SAP' OR source = 'OTHER'"));

        assertAclIsTopLevelConjunct(service.renderWhere(criterias, ACL));
    }

    @Test
    void topLevelOr_withNoOtherCriteria_stillConfinesTheCaller() {
        List<SqlField> criterias = new ArrayList<>();
        criterias.add(advanced("source = 'SAP' OR source = 'OTHER'"));

        assertAclIsTopLevelConjunct(service.renderWhere(criterias, ACL));
    }

    @Test
    void nestedAndInsideOr_cannotEscapeTheDatasetAcl() {
        List<SqlField> criterias = new ArrayList<>();
        criterias.add(basic());
        criterias.add(advanced("source = 'SAP' OR (type = 'alarm' AND status = 'open')"));

        assertAclIsTopLevelConjunct(service.renderWhere(criterias, ACL));
    }

    /**
     * The expression language cannot reintroduce the original defect even in principle: whatever
     * boolean structure a caller writes is rendered as ONE parenthesised term before it becomes a
     * criterion, so it has no way to reach the WHERE root where the ACL lives.
     */
    @Test
    void aRenderedExpressionIsAlwaysASingleTerm() {
        String sql = advanced("type = 'x' OR source = 'y' OR status = 'z'").sql();

        assertTrue(sql.startsWith("(") && sql.endsWith(")"), () -> "not one term: " + sql);
    }

    @Test
    void plainCriteriaOnly_areStillConjoinedWithTheAcl() {
        List<SqlField> criterias = new ArrayList<>();
        criterias.add(basic());

        assertAclIsTopLevelConjunct(service.renderWhere(criterias, ACL));
    }

    @Test
    void readAllCaller_getsNoAclClause() {
        List<SqlField> criterias = new ArrayList<>();
        criterias.add(basic());

        String clause = service.renderWhere(criterias, null);

        assertTrue(clause.contains("type = {t:String}"), () -> "criteria lost: " + clause);
        assertTrue(!clause.contains("data_set_id"), () -> "unexpected ACL for a read-all caller: " + clause);
    }

    @Test
    void noCriteriaAtAll_startsTheClauseWithWhere() {
        String clause = service.renderWhere(new ArrayList<>(), ACL);

        assertEquals(" WHERE " + ACL, clause);
    }
}
