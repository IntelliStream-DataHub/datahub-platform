// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.json.SingleOrList;
import ai.intellistream.datahub.models.events.EventFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Holds the filter family to one contract.
 *
 * <p>The three node filters inherit theirs from {@link NodeFilter}, so the compiler enforces it.
 * {@link EventFilter} cannot inherit — events live in ClickHouse, their id is a UUID string and the
 * table has no name column — so its overlap with the base is a convention, and a convention with
 * nothing checking it is how the family drifted in the first place: four filters that shared almost
 * nothing, each having learned about {@code name} and {@code externalId} at a different time.
 *
 * <p>Reflection over the model classes, so it runs on every build rather than needing a context.
 */
class FilterContractParityTest {

    /**
     * What every filter that can support a concept must call it. Not "every field on
     * {@link NodeFilter}" — {@code id} and {@code name} are deliberately absent from
     * {@link EventFilter}, for reasons stated on that class.
     */
    private static final Set<String> SHARED_WITH_EVENTS = Set.of(
            "externalId", "source", "metadata", "createdTime", "lastUpdatedTime", "dataSetId");

    @ParameterizedTest
    @ValueSource(classes = {DataSetFilter.class, ResourceFilter.class, TimeseriesFilter.class})
    void everyNodeFilterExtendsTheBase(Class<?> filter) {
        assertTrue(NodeFilter.class.isAssignableFrom(filter),
                filter.getSimpleName() + " must extend NodeFilter so the shared criteria have one implementation");
    }

    @ParameterizedTest
    @ValueSource(classes = {ResourceFilter.class, TimeseriesFilter.class})
    void filtersOnNodesThatLiveInADataSetGetDataSetIds(Class<?> filter) {
        assertTrue(DataSetScopedFilter.class.isAssignableFrom(filter),
                filter.getSimpleName() + " must extend DataSetScopedFilter to inherit dataSetId");
    }

    @Test
    void dataSetFilterIsNotDataSetScoped() {
        // A data set is what other nodes are scoped by; asking which data set it belongs to is not
        // a question this API answers.
        assertTrue(!DataSetScopedFilter.class.isAssignableFrom(DataSetFilter.class),
                "DataSetFilter must not inherit dataSetId");
    }

    /**
     * The subclasses carry only what is genuinely their own. A field here that also exists on the
     * base means one of the two is dead — and which one wins depends on shadowing rules no caller
     * can see from the wire.
     */
    @ParameterizedTest
    @ValueSource(classes = {DataSetFilter.class, ResourceFilter.class, TimeseriesFilter.class})
    void noSubclassRedeclaresABaseField(Class<?> filter) {
        Set<String> inherited = fieldNames(NodeFilter.class);
        inherited.addAll(fieldNames(DataSetScopedFilter.class));
        List<String> shadowed = new ArrayList<>(fieldNames(filter));
        shadowed.retainAll(inherited);
        assertTrue(shadowed.isEmpty(),
                filter.getSimpleName() + " redeclares base fields " + shadowed);
    }

    /**
     * EventFilter names and types the shared concepts exactly as the base does. It is the half of
     * the contract the compiler cannot reach.
     */
    @Test
    void eventFilterMatchesTheBaseFieldForField() {
        List<String> mismatches = new ArrayList<>();
        for (String name : SHARED_WITH_EVENTS) {
            Field base = declaredField(NodeFilter.class, name);
            if (base == null) {
                base = declaredField(DataSetScopedFilter.class, name);
            }
            assertNotNull(base, name + " is listed as shared but is on neither NodeFilter nor DataSetScopedFilter");

            Field event = declaredField(EventFilter.class, name);
            if (event == null) {
                mismatches.add(name + ": missing from EventFilter");
            } else if (!event.getGenericType().equals(base.getGenericType())) {
                mismatches.add(name + ": " + event.getGenericType() + " on EventFilter, "
                        + base.getGenericType() + " on the base");
            }
        }
        if (!mismatches.isEmpty()) {
            fail("EventFilter has drifted from NodeFilter:\n  " + String.join("\n  ", mismatches));
        }
    }

    /**
     * The dead fields this refactor removed, named so they cannot quietly return. Each was
     * declared, documented, and read by nothing.
     */
    @Test
    void removedDeadFieldsStayRemoved() {
        assertEquals(null, declaredField(EventFilter.class, "id"),
                "EventFilter.id was typed Long while EventModel.id is a String UUID; nothing read it");
        assertEquals(null, declaredField(ResourceFilter.class, "name"),
                "name belongs to NodeFilter; redeclaring it here would shadow the base");
        assertEquals(null, declaredField(TimeseriesFilter.class, "dataSetId"),
                "dataSetId belongs to DataSetScopedFilter; redeclaring it here would shadow the base");
        for (String dead : List.of("minCreatedTime", "maxCreatedTime", "minLastUpdatedTime", "maxLastUpdatedTime")) {
            assertEquals(null, declaredField(NodeFilter.class, dead),
                    "DataFilter's " + dead + " was never read by any query");
        }
        for (Class<?> filter : List.of(NodeFilter.class, EventFilter.class)) {
            assertEquals(null, declaredField(filter, "externalIdPrefix"),
                    "externalIdPrefix folded into the externalId pattern list; \"sap_*\" says the same thing");
        }
        for (String gone : List.of("metadataKey", "metadataValue")) {
            assertEquals(null, declaredField(TimeseriesFilter.class, gone),
                    gone + " is superseded: a null metadata value expresses what the pair was for");
        }
    }

    /**
     * The singular names are lists, not the scalars they used to be.
     *
     * <p>{@code source}, {@code type}, {@code subType}, {@code status}, {@code unit} and
     * {@code unitExternalId} were each once a single exact-match {@code String}, which is why
     * "alarms and warnings" took two calls. They were widened into lists under plural names, and the
     * names have since come back to the singular because every one of them takes a bare value as
     * well — so a caller asking for one thing writes what they always wrote.
     *
     * <p>That makes a scalar redeclaration the specific regression worth guarding: the type is now
     * the only thing distinguishing today's field from the one it replaced, and a body that used to
     * mean "exactly this" would silently start meaning it again. Asserting the field is simply
     * absent, as the removal test above once did, no longer says anything true.
     */
    @Test
    void theSingularNamesAreListsNotTheScalarsTheyReplaced() {
        assertListOfString(NodeFilter.class, "externalId");
        assertListOfString(NodeFilter.class, "name");
        assertListOfString(NodeFilter.class, "source");
        assertListOfString(EventFilter.class, "externalId");
        assertListOfString(EventFilter.class, "source");
        assertListOfString(EventFilter.class, "type");
        assertListOfString(EventFilter.class, "subType");
        assertListOfString(EventFilter.class, "status");
        assertListOfString(TimeseriesFilter.class, "unit");
        assertListOfString(TimeseriesFilter.class, "unitExternalId");
        assertListOfString(TimeseriesFilter.class, "valueType");
        assertListOfString(ResourceFilter.class, "nodeType");
    }

    private static void assertListOfString(Class<?> filter, String name) {
        Field f = declaredField(filter, name);
        assertNotNull(f, filter.getSimpleName() + "." + name + " is missing");
        String why = filter.getSimpleName() + "." + name + " must stay List<String>; a bare String "
                + "would resurrect the exact-match scalar it replaced under the same name";
        assertEquals(List.class, f.getType(), why);
        assertEquals(String.class,
                ((java.lang.reflect.ParameterizedType) f.getGenericType()).getActualTypeArguments()[0], why);
    }

    private static Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Set<String> fieldNames(Class<?> type) {
        Set<String> names = new java.util.HashSet<>();
        for (Field f : type.getDeclaredFields()) {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        return names;
    }

    /**
     * Every list on every filter accepts a bare value in its place. A caller passing one source
     * should not have to know it is a list field, and finding out by 400 is a poor way to learn.
     */
    @ParameterizedTest
    @ValueSource(classes = {NodeFilter.class, DataSetScopedFilter.class, DataSetFilter.class,
            ResourceFilter.class, TimeseriesFilter.class, EventFilter.class})
    void everyListFieldAcceptsASingleValue(Class<?> filter) {
        List<String> missing = new ArrayList<>();
        for (Field f : filter.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!Collection.class.isAssignableFrom(f.getType())) {
                continue;
            }
            if (f.getAnnotation(SingleOrList.class) == null) {
                missing.add(f.getName());
            }
        }
        assertTrue(missing.isEmpty(),
                filter.getSimpleName() + " list fields missing @SingleOrList: " + missing);
    }
}
