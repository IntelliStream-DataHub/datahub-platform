// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documentation envelopes are hand-written copies of {@link DataWrapper}, and copies drift.
 *
 * <p>They exist because {@code DataWrapper} carries a fixed {@code @Schema(name = "DataWrapper")},
 * so every generic instantiation would document as one untyped schema. Each copy was written when
 * {@code items} was the whole envelope. {@code DataWrapper} later grew {@code warnings} and
 * {@code nextCursor} — each with a careful note about being absent when empty so no existing client
 * would notice — and none of the copies followed. The published spec therefore described a paged
 * response with no cursor field in it, so a generated client could not page at all while the
 * endpoint descriptions instructed callers to loop on exactly that value.
 *
 * <p>These tests do not try to infer which endpoint uses which envelope; that is what the explicit
 * lists below are for. What they do enforce is that a copy never invents a field the real envelope
 * lacks, and never documents a response-only field as writable.
 */
class SwaggerEnvelopeParityTest {

    /** Envelopes bound to a response whose service calls {@code setNextCursor} with a real value. */
    private static Stream<Class<?>> pagedEnvelopes() {
        return Stream.of(
                AssetDataWrapper.class, ResourceDataWrapper.class, TimeseriesDataWrapper.class,
                DataSetDataWrapper.class, EventDataWrapper.class, SubscriptionDataWrapper.class,
                DatapointsDataWrapper.class, DatapointsCollectionDataWrapper.class);
    }

    /**
     * Envelopes that can carry policy warnings — the write paths that evaluate policies are
     * {@code ResourceService}, {@code TimeseriesService}, {@code DataSetService} and
     * {@code GraphTransferService}, so events, labels, edges and files cannot.
     */
    private static Stream<Class<?>> warningBearingEnvelopes() {
        return Stream.of(
                AssetDataWrapper.class, ResourceDataWrapper.class, TimeseriesDataWrapper.class,
                DataSetDataWrapper.class, ResourceGraphDataWrapper.class);
    }

    private static Set<String> fieldNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Field f : type.getDeclaredFields()) {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        return names;
    }

    private static Field declared(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    @ParameterizedTest(name = "{0} documents nextCursor")
    @MethodSource("pagedEnvelopes")
    @DisplayName("an envelope for a pageable response declares the cursor its endpoint returns")
    void pagedEnvelopesDeclareNextCursor(Class<?> envelope) {
        assertThat(fieldNames(envelope))
                .as("%s is the documented shape of a response that carries nextCursor; without the "
                        + "field a generated client has nothing to read and cannot page",
                        envelope.getSimpleName())
                .contains("nextCursor");
    }

    @ParameterizedTest(name = "{0} documents warnings")
    @MethodSource("warningBearingEnvelopes")
    void warningBearingEnvelopesDeclareWarnings(Class<?> envelope) {
        assertThat(fieldNames(envelope)).contains("warnings");
    }

    /**
     * Both are response-only. The live {@code DataWrapper} does accept them on a request body — a
     * defect in its own right — but publishing that shape would invite clients to send them.
     */
    @ParameterizedTest(name = "{0} marks its mirrored fields read-only")
    @MethodSource("pagedEnvelopes")
    void mirroredFieldsAreReadOnly(Class<?> envelope) {
        for (String name : List.of("nextCursor", "warnings")) {
            Field field = declared(envelope, name);
            if (field == null) {
                continue;
            }
            Schema schema = field.getAnnotation(Schema.class);
            assertThat(schema)
                    .as("%s.%s needs a @Schema to be marked read-only", envelope.getSimpleName(), name)
                    .isNotNull();
            assertThat(schema.accessMode())
                    .as("%s.%s is response-only", envelope.getSimpleName(), name)
                    .isEqualTo(Schema.AccessMode.READ_ONLY);
        }
    }

    /**
     * The copies may lag the real envelope — that is what the lists above are for — but they must
     * never describe a field it does not have. A field here that {@code DataWrapper} lacks is
     * documentation of something no response can contain.
     */
    @ParameterizedTest(name = "{0} invents no fields")
    @MethodSource({"pagedEnvelopes", "warningBearingEnvelopes"})
    void copiesDoNotInventFields(Class<?> envelope) {
        Set<String> real = new LinkedHashSet<>(fieldNames(DataWrapper.class));
        real.addAll(fieldNames(GraphDataWrapper.class));

        assertThat(real)
                .as("%s declares fields the real envelopes do not have", envelope.getSimpleName())
                .containsAll(fieldNames(envelope));
    }
}
