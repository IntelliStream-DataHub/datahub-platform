// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import ai.intellistream.datahub.flow.definition.Template.Scope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateTest {

    @Test
    void theFirstSegmentDecidesTheScope() {
        Template t = Template.parse("${parameters.limit}/${run.window.start}/${secrets.db}"
                + "/${item.last}/${timeseries.externalId}");

        assertThat(t.references()).extracting(Template.Reference::scope, Template.Reference::key)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Scope.PARAMETERS, "limit"),
                        org.assertj.core.groups.Tuple.tuple(Scope.RUN, "window.start"),
                        org.assertj.core.groups.Tuple.tuple(Scope.SECRETS, "db"),
                        org.assertj.core.groups.Tuple.tuple(Scope.ITEM, "last"),
                        org.assertj.core.groups.Tuple.tuple(Scope.ATTRIBUTE, "timeseries.externalId"));
        assertThat(t.refersToItem()).isTrue();
        assertThat(t.isSingleReference()).isFalse();
        assertThat(t).hasToString("${parameters.limit}/${run.window.start}/${secrets.db}"
                + "/${item.last}/${timeseries.externalId}");
    }

    @Test
    void malformedReferencesAreRejected() {
        assertThatThrownBy(() -> Template.parse("${}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Template.parse("a ${b c}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Template.parse("${unclosed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Template.parse("${parameters}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Template.parse("costs $5").parts()).hasSize(1);
    }

    @Test
    void aSingleReferenceKeepsItsType() {
        Instant start = Instant.parse("2026-10-01T10:00:00Z");
        Object value = Template.parse("${run.window.start}").substitute(r -> start);
        assertThat(value).isEqualTo(start);
    }

    @Test
    void textReadsValuesTheWayAPersonWouldWriteThem() {
        Map<String, Object> values = Map.of("n", new BigDecimal("140.0"), "f", 1.5d,
                "d", Duration.ofMinutes(5), "i", Instant.parse("2026-10-01T10:00:00Z"));
        Object value = Template.parse("${parameters.n}|${parameters.f}|${parameters.d}|${parameters.i}")
                .substitute(r -> values.get(r.key()));
        assertThat(value).isEqualTo("140|1.5|PT5M|2026-10-01T10:00:00Z");
    }

    @Test
    void whatIsNotResolvedStaysATemplate() {
        Object value = Template.parse("${parameters.p}_${timeseries.externalId}_x")
                .substitute(r -> r.scope() == Scope.PARAMETERS ? "a" : null);
        assertThat(value).isInstanceOf(Template.class).hasToString("a_${timeseries.externalId}_x");

        Object done = ((Template) value).substitute(r -> "ts1");
        assertThat(done).isEqualTo("a_ts1_x");
    }
}
