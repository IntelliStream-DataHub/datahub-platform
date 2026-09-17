// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.controllers.errors.schema.ApiProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.DatapointBlockProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.DeleteRefusedProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.DuplicateProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.PartialWriteProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.RestoreRefusedProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.ValidationProblem;
import ai.intellistream.datahub.api.filters.RequestIdFilter;
import ai.intellistream.datahub.validation.FieldValidationError;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the OpenAPI promises about an error body is what the API actually sends.
 *
 * <h2>Why the schema is not {@code ProblemDetail.class}</h2>
 * It was, briefly, and it documented the wrong shape. Spring's {@link ProblemDetail} keeps
 * extension members in a {@code Map} behind {@code getProperties()}, so swagger-core generates a
 * schema with a nested {@code properties} object. The wire has no such member: the Jackson mixin
 * flattens extensions to the top level, and it is applied by the message converter, so schema
 * generation never sees it. The published schema therefore advertised a {@code properties} map that
 * never arrives and omitted {@code fields}, {@code duplicated}, {@code blockedBy} and
 * {@code missing}, which always do.
 *
 * <p>The hand-written schema classes fix that, and inherit the failure mode of every hand-written
 * schema in this repo — the response envelopes drifted the moment {@code nextCursor} was added to
 * the real one and not to theirs. So this renders each problem through MVC, exactly as a caller
 * receives it, and compares the emitted members against the declared ones. Adding an extension to
 * {@link Problems} without declaring it fails here rather than shipping as an undocumented member.
 */
class ProblemSchemaParityTest {

    /** Serves whatever problem the test asks for, so the real message converter renders it. */
    @RestController
    static class Problematic {
        static final Map<String, Supplier<ProblemDetail>> CASES = new HashMap<>();

        @GetMapping("/problem/{name}")
        ProblemDetail serve(@PathVariable String name) {
            return CASES.get(name).get();
        }
    }

    // The advice and filter that add requestId and retry in the running API, so the body is the one a caller gets.
    private static final MockMvc MVC = MockMvcBuilders.standaloneSetup(new Problematic())
            .setControllerAdvice(new ProblemResponseAdvice())
            .addFilters(new RequestIdFilter())
            .build();

    /** Every problem {@link Problems} can build, paired with the class that documents it. */
    static Stream<Object[]> documentedProblems() {
        return Stream.of(
                new Object[] {"validation", ValidationProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.fieldValidation(List.of(
                                new FieldValidationError("source",
                                        new String[] {"resource.source.max.length.error"},
                                        new Object[] {129},
                                        "Source max length is 128 characters.")))},
                new Object[] {"binding", ValidationProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.badRequest("Bad.",
                                new FieldErrors().addFieldError("externalId", "must not be blank").asList())},
                new Object[] {"bare-bad-request", ValidationProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.badRequest("Bad.")},
                new Object[] {"duplicate", DuplicateProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.duplicate("Taken.",
                                List.of(Map.of("externalId", "sensor_temp_room_a")))},
                new Object[] {"constraint-duplicate", DuplicateProblem.class,
                        (Supplier<ProblemDetail>) () -> new DataIntegrityViolationExceptionHandler().handle(
                                new DataIntegrityViolationException("duplicate key",
                                        new org.hibernate.exception.ConstraintViolationException("duplicate key",
                                                new SQLException("duplicate key"), "label_hash_key")))},
                new Object[] {"optimistic-lock", DuplicateProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.conflict(Problems.OPTIMISTIC_LOCK, "Retry.")},
                new Object[] {"datapoint-block", DatapointBlockProblem.class,
                        (Supplier<ProblemDetail>) () -> new DatapointBlockExceptionHandler().handle(
                                DatapointBlockRejectedException.externalIdMismatch(3, List.of(1041L, 1042L)))
                                .getBody()},
                new Object[] {"delete-refused", DeleteRefusedProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.deleteBlocked(Problems.REFERENCED,
                                "Still referenced.",
                                List.of(Map.of("subscriptionExternalId", "fleet_dashboard")))},
                new Object[] {"restore-refused", RestoreRefusedProblem.class,
                        (Supplier<ProblemDetail>) () -> {
                            ProblemDetail problem = Problems.conflict(null, "Its original folder is gone.");
                            problem.setProperty("reason", "folder-missing");
                            return problem;
                        }},
                new Object[] {"not-found", ApiProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.notFound("Gone.")},
                new Object[] {"internal", ApiProblem.class,
                        (Supplier<ProblemDetail>) () -> Problems.internal("Oops.")});
    }

    @ParameterizedTest(name = "{0} is fully described by {1}")
    @MethodSource("documentedProblems")
    @DisplayName("every member the API emits is declared on the schema that documents it")
    void emittedMembersAreDocumented(String name, Class<?> schema, Supplier<ProblemDetail> problem)
            throws Exception {
        Set<String> emitted = emitted(name, problem);
        // Never a member on the wire — if it appears, the mixin stopped being applied and every
        // extension has silently moved one level down.
        assertThat(emitted)
                .as("%s: extensions must be flattened, not nested under `properties`", name)
                .doesNotContain("properties");

        assertThat(declaredFields(schema))
                .as("%s: the API emits members that %s does not declare, so the published schema "
                        + "understates the body", name, schema.getSimpleName())
                .containsAll(emitted);
    }

    @Test
    @DisplayName("no schema declares a member the API never emits")
    @SuppressWarnings("unchecked")
    void declaredMembersAreReachable() throws Exception {
        Set<String> everyEmitted = new HashSet<>(List.of("type", "title", "status", "detail", "instance"));
        for (Object[] row : documentedProblems().toList()) {
            everyEmitted.addAll(emitted((String) row[0], (Supplier<ProblemDetail>) row[2]));
        }
        // PartialWriteProblem's `missing` is set by the controller rather than by Problems, so it
        // has no factory above; naming it here keeps the check honest instead of loosening it.
        everyEmitted.add("missing");

        for (Class<?> schema : List.of(ApiProblem.class, ValidationProblem.class, DuplicateProblem.class,
                DeleteRefusedProblem.class, PartialWriteProblem.class, RestoreRefusedProblem.class,
                DatapointBlockProblem.class)) {
            assertThat(everyEmitted)
                    .as("%s declares a member nothing produces — a promise the API cannot keep",
                            schema.getSimpleName())
                    .containsAll(declaredFields(schema));
        }
    }

    /**
     * The published list of types is what a client writes its branches against, and nothing
     * stopped a new constant from missing it, so every one on {@link Problems} must appear.
     */
    @Test
    @DisplayName("the schema lists every problem type Problems declares")
    void everyDeclaredTypeIsListed() throws Exception {
        String listed = ApiProblem.class.getDeclaredField("type").getAnnotation(Schema.class).description();
        for (Field field : Problems.class.getDeclaredFields()) {
            if (!URI.class.equals(field.getType()) || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String slug = field.get(null).toString().substring(Problems.BASE.length());
            assertThat(listed)
                    .as("ApiProblem.type does not list %s (%s)", field.getName(), slug)
                    .contains("`.../errors/" + slug + "`");
        }
    }

    /** The top-level members of the body MVC renders for this problem. */
    @SuppressWarnings("unchecked")
    private static Set<String> emitted(String name, Supplier<ProblemDetail> problem) throws Exception {
        Problematic.CASES.put(name, problem);
        String body = MVC.perform(get(name)).andReturn().getResponse().getContentAsString();
        return new HashSet<>(new tools.jackson.databind.json.JsonMapper().readValue(body, Map.class).keySet());
    }

    /** The schema's own fields plus everything it inherits — swagger-core inlines both. */
    private static Set<String> declaredFields(Class<?> schema) {
        Set<String> names = new HashSet<>();
        for (Class<?> c = schema; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                names.add(f.getName());
            }
        }
        return names;
    }

    private static org.springframework.test.web.servlet.RequestBuilder get(String name) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/problem/" + name).accept(MediaType.ALL);
    }
}
