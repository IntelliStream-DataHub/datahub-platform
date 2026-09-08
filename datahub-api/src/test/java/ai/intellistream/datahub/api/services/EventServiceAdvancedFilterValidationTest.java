// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.models.events.AdvancedFilter;
import ai.intellistream.datahub.models.events.AdvancedFilterOperator;
import ai.intellistream.datahub.models.events.AdvancedNotFilter;
import ai.intellistream.datahub.models.events.Operator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advanced filter is validated before it reaches the query builder, so the two shapes that
 * used to surface as a 500 now surface as a 400.
 *
 * <p>{@code AdvancedEventFilter.validate()} was supposed to cover the first of these. It was never
 * called from anywhere, and would not have caught anything if it had been: it inspects
 * {@code filter.getProperty()}, while a leaf deserialised from JSON carries its property on
 * {@code filterOperator.getProperty()}.
 */
class EventServiceAdvancedFilterValidationTest {

    private static AdvancedFilter leaf(String property, String value) {
        AdvancedFilterOperator operator = new AdvancedFilterOperator();
        operator.setOperator(Operator.equals);
        operator.setProperty(property == null ? null : List.of(property));
        operator.setValue(value);
        AdvancedFilter filter = new AdvancedFilter();
        filter.setFilterOperator(operator);
        return filter;
    }

    /** What `{"equals": {...}}` with an unbound operator name deserialises to: no filterOperator. */
    private static AdvancedFilter leafWithNoOperator() {
        return new AdvancedFilter();
    }

    @Test
    void nullFilterIsFine() {
        assertDoesNotThrow(() -> EventService.assertAdvancedFilterIsUsable(null));
    }

    @Test
    void knownPropertiesPass() {
        AdvancedFilter or = new AdvancedFilter();
        or.setOr(List.of(leaf("source", "SAP"), leaf("externalId", "PO-1")));
        assertDoesNotThrow(() -> EventService.assertAdvancedFilterIsUsable(or));
    }

    @Test
    void unknownPropertyIsRejectedByName() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> EventService.assertAdvancedFilterIsUsable(leaf("descriptionn", "x")));

        assertTrue(fieldsMention(e, "descriptionn"),
                () -> "the offending property should be named in the error fields: "
                        + e.getError().getError().getFields());
    }

    @Test
    void unknownPropertyNestedInsideOrIsStillFound() {
        AdvancedFilter or = new AdvancedFilter();
        or.setOr(List.of(leaf("source", "SAP"), leaf("nope", "x")));

        assertThrows(BadRequestException.class, () -> EventService.assertAdvancedFilterIsUsable(or));
    }

    @Test
    void unknownPropertyNestedInsideNotIsStillFound() {
        AdvancedNotFilter inner = new AdvancedNotFilter(null, null, null, null, null, null, null);
        AdvancedFilterOperator operator = new AdvancedFilterOperator();
        operator.setOperator(Operator.equals);
        operator.setProperty(List.of("nope"));
        operator.setValue("x");
        inner.setFilterOperator(operator);
        AdvancedFilter filter = new AdvancedFilter();
        filter.setNot(inner);

        assertThrows(BadRequestException.class, () -> EventService.assertAdvancedFilterIsUsable(filter));
    }

    /**
     * `containsAll`, `containsAny` and `exists` are declared in {@code Operator} but have no
     * {@code @JsonProperty} setter, and the Rust SDK also offers `range` and `isSet`. All of them
     * deserialise to a leaf with a null filterOperator, which used to NPE in buildAdvancedFilter.
     */
    @Test
    void leafWithNoBoundOperatorIsRejectedInsteadOfNpeing() {
        assertThrows(BadRequestException.class,
                () -> EventService.assertAdvancedFilterIsUsable(leafWithNoOperator()));
    }

    @Test
    void leafWithNoPropertyIsRejectedInsteadOfNpeing() {
        assertThrows(BadRequestException.class,
                () -> EventService.assertAdvancedFilterIsUsable(leaf(null, "x")));
    }

    private static boolean fieldsMention(BadRequestException e, String needle) {
        return e.getError().getError().getFields().stream()
                .flatMap(f -> f.values().stream())
                .anyMatch(v -> v.contains(needle));
    }
}
