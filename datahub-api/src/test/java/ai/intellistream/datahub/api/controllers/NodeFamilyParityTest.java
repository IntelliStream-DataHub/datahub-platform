// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-entity parity for the {@code NodeEntity} family plus Events.
 *
 * <p>Asset, Resource, Timeseries, Dataset, Policy and Function are rows in one table; Events sit
 * beside them with a comparable CRUD surface. They drifted apart operation by operation — create
 * returning 200 for two types and 201 for the rest, delete returning 200 for one and 204 for the
 * rest, two of them missing a single-item GET entirely — because nothing ever compared them to each
 * other. Per-type tests cannot catch that: each one passes on its own.
 *
 * <p>This is that comparison. It reflects over the controllers rather than starting a context, so it
 * stays fast enough to run on every build. Adding a node type that does not satisfy these should
 * fail here, not in review.
 */
class NodeFamilyParityTest {

    /** The convergence target. Label/Edge/Subscription/File are separate tables — see the audit. */
    private static Stream<Class<?>> nodeFamilyControllers() {
        return Stream.of(
                ResourceController.class,
                TimeseriesController.class,
                DataSetController.class,
                EventController.class,
                PolicyController.class,
                FunctionController.class);
    }

    // ---- Wire-side discriminator registry ------------------------------------------------------

    /**
     * The wire-side dispatch table ({@code NodeModelSubtypes.BY_TYPE_LABEL} in datahub-api-model)
     * and the entity-side authority ({@code TypeLabels.ALL} in datahub-infra) must name the same
     * type-labels. They live in different modules and cannot reference each other, so this is the
     * one place they are held equal: adding a node type to one without the other fails here, not
     * in review.
     */
    @Test
    @DisplayName("F0: the label-keyed deserializer registry matches TypeLabels")
    void subtypeRegistryMatchesTypeLabels() {
        assertThat(NodeModelSubtypes.BY_TYPE_LABEL.keySet())
                .isEqualTo(TypeLabels.ALL);
    }

    // ---- Endpoint surface ----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} exposes a single-item GET")
    @MethodSource("nodeFamilyControllers")
    @DisplayName("F1: every type can be fetched by id on its own")
    void everyTypeHasASingleItemGet(Class<?> controller) {
        assertThat(pathsFor(controller, RequestMethod.GET))
                .as("%s should expose GET /{id}", controller.getSimpleName())
                .anyMatch(p -> p.matches(".*/\\{[A-Za-z]*[Ii]d\\}$"));
    }

    // ---- Status codes --------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} create returns 201")
    @MethodSource("nodeFamilyControllers")
    @DisplayName("F7: create is 201 everywhere, not 200 for some types")
    void createReturns201(Class<?> controller) {
        Method create = methodForPath(controller, "/create", RequestMethod.POST);
        assertThat(create).as("%s should have POST /create", controller.getSimpleName()).isNotNull();
        assertThat(declaredSuccessStatuses(create))
                .as("%s.%s should document 201", controller.getSimpleName(), create.getName())
                .contains("201");
    }

    @ParameterizedTest(name = "{0} delete returns 204")
    @MethodSource("nodeFamilyControllers")
    @DisplayName("F8: delete is 204 everywhere, not 200 for events")
    void deleteReturns204(Class<?> controller) {
        Method delete = methodForPath(controller, "/delete", RequestMethod.POST);
        assertThat(delete).as("%s should have POST /delete", controller.getSimpleName()).isNotNull();
        // Exactly 204, not merely "204 among others": PolicyController documented a 200 with a
        // body alongside it, copied from the update endpoint, which a contains() check let stand.
        assertThat(declaredSuccessStatuses(delete))
                .as("%s.%s should document 204 and nothing else", controller.getSimpleName(), delete.getName())
                .containsExactly("204");
    }

    @ParameterizedTest(name = "{0} delete accepts both POST and DELETE")
    @MethodSource("nodeFamilyControllers")
    @DisplayName("delete is reachable by both verbs on every type")
    void deleteAcceptsBothVerbs(Class<?> controller) {
        Method delete = methodForPath(controller, "/delete", RequestMethod.POST);
        Set<RequestMethod> verbs = verbsFor(delete);
        assertThat(verbs)
                .as("%s delete should accept POST and DELETE", controller.getSimpleName())
                .contains(RequestMethod.POST, RequestMethod.DELETE);
    }

    // ---- Wire shape ----------------------------------------------------------------------------

    @Test
    @DisplayName("F24: no id-shaped field is emitted as a raw number")
    void idFieldsSerializeAsStrings() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : IdSerializationCheck.WIRE_TYPES) {
            offenders.addAll(IdSerializationCheck.rawNumericIdFields(type));
        }
        assertThat(offenders)
                .as("""
                    Node ids are 64-bit xxHash values. A JavaScript client reading one as a JSON \
                    number rounds it through a double and silently corrupts anything past 2^53, so \
                    every id-shaped field is serialized with ToStringSerializer. Add the annotation \
                    to any field listed here, or rename it if it is not an id.""")
                .isEmpty();
    }

    // ---- Reflection helpers --------------------------------------------------------------------

    private static Set<String> pathsFor(Class<?> controller, RequestMethod verb) {
        Set<String> paths = new LinkedHashSet<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (verbsFor(m).contains(verb)) {
                paths.addAll(pathsOf(m));
            }
        }
        return paths;
    }

    private static Method methodForPath(Class<?> controller, String suffix, RequestMethod verb) {
        for (Method m : controller.getDeclaredMethods()) {
            if (verbsFor(m).contains(verb) && pathsOf(m).stream().anyMatch(p -> p.endsWith(suffix))) {
                return m;
            }
        }
        return null;
    }

    private static List<String> pathsOf(Method m) {
        List<String> out = new ArrayList<>();
        for (Annotation a : m.getAnnotations()) {
            out.addAll(Arrays.asList(MappingAnnotations.paths(a)));
        }
        return out;
    }

    private static Set<RequestMethod> verbsFor(Method m) {
        Set<RequestMethod> out = new LinkedHashSet<>();
        if (m == null) return out;
        for (Annotation a : m.getAnnotations()) {
            out.addAll(Arrays.asList(MappingAnnotations.verbs(a)));
        }
        return out;
    }

    /**
     * The success statuses the handler documents via {@code @ApiResponse}. Reading the annotation
     * rather than the body is deliberate: the OpenAPI spec is the published contract, and the two
     * drifting apart is itself a defect this catches (the audit found four handlers documenting a
     * status they did not return).
     */
    private static Set<String> declaredSuccessStatuses(Method m) {
        Set<String> out = new LinkedHashSet<>();
        if (m == null) return out;
        for (io.swagger.v3.oas.annotations.responses.ApiResponse r
                : m.getAnnotationsByType(io.swagger.v3.oas.annotations.responses.ApiResponse.class)) {
            String code = r.responseCode();
            if (code.startsWith("2")) out.add(code);
        }
        return out;
    }

    /** Kept out of the test body so the mapping-annotation zoo stays in one place. */
    private static final class MappingAnnotations {
        static String[] paths(Annotation a) {
            if (a instanceof RequestMapping r) return merge(r.value(), r.path());
            if (a instanceof org.springframework.web.bind.annotation.GetMapping g) return merge(g.value(), g.path());
            if (a instanceof org.springframework.web.bind.annotation.PostMapping p) return merge(p.value(), p.path());
            if (a instanceof org.springframework.web.bind.annotation.PutMapping p) return merge(p.value(), p.path());
            if (a instanceof org.springframework.web.bind.annotation.DeleteMapping d) return merge(d.value(), d.path());
            if (a instanceof org.springframework.web.bind.annotation.PatchMapping p) return merge(p.value(), p.path());
            return new String[0];
        }

        static RequestMethod[] verbs(Annotation a) {
            if (a instanceof RequestMapping r) return r.method();
            if (a instanceof org.springframework.web.bind.annotation.GetMapping) return new RequestMethod[]{RequestMethod.GET};
            if (a instanceof org.springframework.web.bind.annotation.PostMapping) return new RequestMethod[]{RequestMethod.POST};
            if (a instanceof org.springframework.web.bind.annotation.PutMapping) return new RequestMethod[]{RequestMethod.PUT};
            if (a instanceof org.springframework.web.bind.annotation.DeleteMapping) return new RequestMethod[]{RequestMethod.DELETE};
            if (a instanceof org.springframework.web.bind.annotation.PatchMapping) return new RequestMethod[]{RequestMethod.PATCH};
            return new RequestMethod[0];
        }

        private static String[] merge(String[] a, String[] b) {
            Set<String> all = new LinkedHashSet<>();
            all.addAll(Arrays.asList(a));
            all.addAll(Arrays.asList(b));
            return all.toArray(new String[0]);
        }
    }
}
