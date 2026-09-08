// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import ai.intellistream.datahub.filter.EventFilterParser;
import ai.intellistream.datahub.filter.FilterParseException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class EventFilterRendererTest {

    private Map<String, Object> params = new LinkedHashMap<>();

    private String render(String expression) {
        params = new LinkedHashMap<>();
        return new EventFilterRenderer(params, expression)
                .render(EventFilterParser.parse(expression));
    }

    @Test
    void columnsResolveToPhysicalNamesAndValuesAreBound() {
        assertThat(render("type = 'Alarm'")).isEqualTo("type = {fp0:String}");
        assertThat(params).containsEntry("fp0", "Alarm");

        assertThat(render("subType != 'water'")).isEqualTo("sub_type != {fp0:String}");
        assertThat(render("dataSetId >= 5")).isEqualTo("data_set_id >= {fp0:Int64}");
    }

    /** A literal takes the type of the column beside it, so this binds a DateTime64, not text. */
    @Test
    void aLiteralAdaptsToTheColumnItIsComparedWith() {
        assertThat(render("eventTime > '2026-01-01'")).isEqualTo("event_time > {fp0:DateTime64(3)}");
        assertThat(params).containsEntry("fp0", "2026-01-01 00:00:00.000");
    }

    @Test
    void metadataKeysAreBoundToo() {
        assertThat(render("metadata['site'] = 'bergen'"))
                .isEqualTo("metadata[{fp0:String}] = {fp1:String}");
        assertThat(params).containsEntry("fp0", "site").containsEntry("fp1", "bergen");
    }

    @Test
    void everyCompositeNodeParenthesisesItself() {
        assertThat(render("type = 'a' OR type = 'b' AND status = 'c'"))
                .isEqualTo("(type = {fp0:String} OR (type = {fp1:String} AND status = {fp2:String}))");
    }

    /**
     * The example this language was designed against, in the spelling it actually accepts.
     * Three things are asserted at once: the leading `where` is fine, to_timestamp of a LITERAL is
     * folded away rather than emitted as a call, and the metadata side becomes the null-safe form.
     */
    @Test
    void theWorkedExample() {
        String sql = render("where type NOT LIKE 'pump' AND (subType = 'water' OR subType = 'gas' "
                + "AND to_timestamp(metadata['startTime']) > to_timestamp('2026-01-01 12:30'))");

        assertThat(sql).isEqualTo(
                "(NOT ((type LIKE {fp0:String})) AND (sub_type = {fp1:String} OR "
                        + "(sub_type = {fp2:String} AND "
                        + "parseDateTimeBestEffortOrNull(metadata[{fp3:String}]) > {fp4:DateTime64(3)})))");
        assertThat(sql).doesNotContain("toDateTime");
        assertThat(params).containsEntry("fp4", "2026-01-01 12:30:00.000");
    }

    @Test
    void castSugarRendersIdenticallyToTheFunctionForm() {
        String viaCast = render("metadata['n']::int > 5");
        String viaFunction = render("to_int(metadata['n']) > 5");

        assertThat(viaCast).isEqualTo(viaFunction);
        assertThat(viaCast).isEqualTo("toInt64OrNull(metadata[{fp0:String}]) > {fp1:Int64}");
    }

    @Test
    void convertersOverAColumnAreAlwaysNullSafe() {
        assertThat(render("to_int(metadata['n']) = 1")).contains("toInt64OrNull(");
        assertThat(render("to_number(metadata['n']) = 1")).contains("toFloat64OrNull(");
        assertThat(render("to_timestamp(metadata['t']) > '2026-01-01'"))
                .contains("parseDateTimeBestEffortOrNull(");
        assertThat(render("to_bool(metadata['b']) = true")).contains("multiIf(");
    }

    @Test
    void datePartIsOnePostgresNameOverThreeClickHouseFunctions() {
        assertThat(render("date_part('year', to_timestamp(metadata['t'])) = 2026"))
                .startsWith("toYear(");
        assertThat(render("date_part('month', eventTime) = 1")).startsWith("toMonth(");
        assertThat(render("date_part('day', eventTime) = 1")).startsWith("toDayOfMonth(");
    }

    /** A missing map key is '', never NULL, so IS NULL is rendered as the question meant. */
    @Test
    void isNullOnMetadataAsksWhetherTheKeyIsAbsent() {
        assertThat(render("metadata['nope'] IS NULL"))
                .isEqualTo("(NOT mapContains(metadata, {fp0:String}))");
        assertThat(render("has_key('site')")).isNotNull();
    }

    @Test
    void isNullOnAGenuinelyNullableColumnIsLiteral() {
        assertThat(render("subType IS NULL")).isEqualTo("(sub_type IS NULL)");
    }

    // ------------------------------------------------------------------ rejections

    @Test
    void bareMetadataAgainstANumberNamesTheConverter() {
        FilterParseException e = catchThrowableOfType(
                () -> render("metadata['count'] > 5"), FilterParseException.class);

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).contains("to_number(metadata['count'])");
        assertThat(e.getSuggestedQuery()).isEqualTo("to_number(metadata['count']) > 5");
    }

    @Test
    void aClickHouseFunctionNameIsAnsweredDefinitively() {
        FilterParseException e = catchThrowableOfType(
                () -> render("toDate(metadata['t']) = '2026-01-01'"), FilterParseException.class);

        assertThat(e).isNotNull();
        assertThat(e.getSuggestion()).isEqualTo("to_date");
        assertThat(e.getMessage()).contains("ClickHouse spelling");
        assertThat(e.getSuggestedQuery()).isEqualTo("to_date(metadata['t']) = '2026-01-01'");
    }

    @Test
    void aPhysicalColumnNameIsAnsweredDefinitively() {
        FilterParseException e = catchThrowableOfType(
                () -> render("sub_type = 'water'"), FilterParseException.class);

        assertThat(e).isNotNull();
        assertThat(e.getSuggestion()).isEqualTo("subType");
        assertThat(e.getSuggestedQuery()).isEqualTo("subType = 'water'");
    }

    @Test
    void aTypoFallsBackToEditDistance() {
        FilterParseException e = catchThrowableOfType(
                () -> render("externaId = 'x'"), FilterParseException.class);

        assertThat(e).isNotNull();
        assertThat(e.getSuggestion()).isEqualTo("externalId");
    }

    @Test
    void bannedFunctionsAreNotInTheRegistry() {
        for (String banned : new String[]{"url", "file", "s3", "remote", "dictGet", "sleep",
                "arrayJoin", "currentUser", "getSetting", "hostName", "version"}) {
            assertThatThrownBy(() -> render(banned + "('x') = 'y'"))
                    .as("must be rejected: %s", banned)
                    .isInstanceOf(FilterParseException.class);
        }
    }

    @Test
    void wrongArityAndUnknownPartsAreRejected() {
        assertThatThrownBy(() -> render("to_int(metadata['a'], 2) = 1"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("argument");
        assertThatThrownBy(() -> render("date_part('week', eventTime) = 1"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("week");
        assertThatThrownBy(() -> render("metadata['n']::money = 1"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("money");
    }

    @Test
    void aMalformedDateIsRejectedHereRatherThanByClickHouse() {
        assertThatThrownBy(() -> render("eventTime > 'yesterday'"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("not a date");
    }

    /**
     * The property the whole design rests on: whatever the caller wrote, none of it reaches the
     * SQL. Values live in params; the SQL is keywords, mapped column names and placeholders.
     */
    @Test
    void noCallerSuppliedTextEverReachesTheSql() {
        String sql = render("externalId LIKE 'ACME-%' AND metadata['owner'] = 'o''brien' "
                + "AND type IN ('Alarm','Warning')");

        for (String value : new String[]{"ACME-%", "owner", "o'brien", "Alarm", "Warning"}) {
            assertThat(sql).as("value leaked into SQL: %s", value).doesNotContain(value);
        }
        assertThat(params.values()).contains("ACME-%", "owner", "o'brien", "Alarm", "Warning");
    }

    @Test
    void injectionPayloadsCannotEscapeAParameter() {
        String sql = render("source = ''' OR 1=1 --'");

        assertThat(sql).isEqualTo("source = {fp0:String}");
        assertThat(params).containsEntry("fp0", "' OR 1=1 --");
    }
}
