// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import ai.intellistream.datahub.models.events.EventRetreiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The envelope every filter endpoint shares: one page size, defended the same way.
 *
 * <p>These four drifted apart quietly — two defaulted to 100 and two to 1000, and two typed the
 * field {@code Integer} while two used {@code int}, so an explicit {@code "limit": null} was a 400
 * on half the API and accepted on the other half. Nothing compared them to each other, which is the
 * same gap {@code FilterContractParityTest} closes for the filter bodies.
 */
class RetrieverEnvelopeTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final Class<?>[] RETRIEVERS = {
            DataSetRetreiver.class, ResourceRetreiver.class, TimeseriesRetreiver.class, EventRetreiver.class};

    @ParameterizedTest
    @ValueSource(classes = {DataSetRetreiver.class, ResourceRetreiver.class, TimeseriesRetreiver.class,
            EventRetreiver.class})
    void limitIsAPrimitiveIntSoItCanNeverArriveNull(Class<?> retriever) throws Exception {
        Field limit = retriever.getDeclaredField("limit");
        assertEquals(int.class, limit.getType(),
                retriever.getSimpleName() + ".limit must be a primitive: an Integer can be null, and "
                        + "Query.setMaxResults(null) is an NPE rather than a 400");
    }

    @ParameterizedTest
    @ValueSource(classes = {DataSetRetreiver.class, ResourceRetreiver.class, TimeseriesRetreiver.class,
            EventRetreiver.class})
    void everyRetrieverDefaultsToTheSamePageSize(Class<?> retriever) throws Exception {
        Object instance = retriever.getDeclaredConstructor().newInstance();
        Method getLimit = retriever.getMethod("getLimit");
        assertEquals(FilterDefaults.DEFAULT_LIMIT, getLimit.invoke(instance),
                retriever.getSimpleName() + " must default to the shared page size");
    }

    /** An absent, null or non-positive limit all mean "you decide", never "return nothing". */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"limit\":null}", "{\"limit\":0}", "{\"limit\":-5}"})
    void noBodyCanTalkTheLimitDownToZero(String body) {
        for (Class<?> retriever : RETRIEVERS) {
            Object parsed = mapper.readValue(body, retriever);
            int limit = (int) invokeGetLimit(parsed);
            assertEquals(FilterDefaults.DEFAULT_LIMIT, limit,
                    retriever.getSimpleName() + " parsing " + body
                            + ": SQL reads LIMIT 0 as 'return nothing', which is indistinguishable "
                            + "from 'nothing matched'");
        }
    }

    @Test
    void anExplicitLimitIsHonoured() {
        for (Class<?> retriever : RETRIEVERS) {
            Object parsed = mapper.readValue("{\"limit\":42}", retriever);
            assertEquals(42, invokeGetLimit(parsed), retriever.getSimpleName());
        }
    }

    /** The shared default must stay a compile-time constant so @Max can reference it. */
    @Test
    void theLimitBoundsAreCompileTimeConstants() throws Exception {
        for (String name : new String[]{"DEFAULT_LIMIT", "MAX_LIMIT"}) {
            Field f = FilterDefaults.class.getDeclaredField(name);
            assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()), name);
        }
        assertTrue(FilterDefaults.DEFAULT_LIMIT <= FilterDefaults.MAX_LIMIT);
    }

    private static Object invokeGetLimit(Object retriever) {
        try {
            return retriever.getClass().getMethod("getLimit").invoke(retriever);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
