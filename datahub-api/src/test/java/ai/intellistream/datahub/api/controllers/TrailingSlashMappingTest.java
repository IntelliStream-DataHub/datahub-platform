// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One spelling per URL: no mapping ends in a trailing slash.
 *
 * <p>{@code /timeseries} and {@code /timeseries/} are different URIs under RFC 3986, and Spring
 * stopped treating them as one some releases ago. Two controllers had re-added the old behaviour by
 * hand — a {@code @Hidden} {@code value = {"/"}} twin delegating to the real handler — and the
 * timeseries twin had drifted to a different default {@code limit}, so the same listing returned
 * 1000 rows or 100 depending on one character in the URL.
 *
 * <p>The cost is not only the drift. Every path-matching layer has to agree about which spellings
 * exist: {@code SecurityConfig} matches {@code "/timeseries/datapoints/listen"} once, while
 * {@code StreamingEndpoints} spells both forms by hand because a servlet filter sees the raw URI.
 * A rule guarding one spelling and a handler answering the other is the shape of an auth bypass.
 *
 * <p>Scanned rather than listed, so a controller added later is covered without anyone remembering
 * to add it here.
 */
class TrailingSlashMappingTest {

    private static final String CONTROLLER_PACKAGE = "ai.intellistream.datahub.api.controllers";

    private static List<Class<?>> restControllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned but could not load " + definition.getBeanClassName(), e);
            }
        }
        return found;
    }

    /** Every pattern the class and its handler methods declare, as written. */
    private static List<String> patternsOf(Class<?> controller) {
        List<String> patterns = new ArrayList<>();
        RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (onClass != null) {
            patterns.addAll(List.of(onClass.value()));
            patterns.addAll(List.of(onClass.path()));
        }
        for (Method method : controller.getDeclaredMethods()) {
            // findMergedAnnotation resolves @GetMapping/@PostMapping/... to their @RequestMapping meta.
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                patterns.addAll(List.of(mapping.value()));
                patterns.addAll(List.of(mapping.path()));
            }
        }
        return patterns;
    }

    @Test
    @DisplayName("the scan finds the controllers it is meant to check")
    void theScanIsActuallyFindingControllers() {
        List<Class<?>> controllers = restControllers();

        // A guard on the guard: a scan that silently finds nothing would pass every assertion below.
        assertThat(controllers).hasSizeGreaterThanOrEqualTo(15);
        assertThat(controllers).contains(TimeseriesController.class, UnitController.class);
    }

    @Test
    @DisplayName("no request mapping ends in a trailing slash")
    void noMappingEndsInATrailingSlash() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : restControllers()) {
            for (String pattern : patternsOf(controller)) {
                // "/" alone is the same defect spelled shortest; "/**" is a wildcard, not a slash.
                if (pattern.endsWith("/") && !pattern.endsWith("**")) {
                    offenders.add(controller.getSimpleName() + " -> \"" + pattern + "\"");
                }
            }
        }
        assertThat(offenders)
                .as("Map the bare path instead. A trailing-slash twin is a second URL for one "
                        + "resource, and the two drift: %s", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("the listings that had slash twins still answer on their bare path")
    void theBarePathsSurvived() {
        assertThat(patternsOf(TimeseriesController.class)).contains("");
        assertThat(patternsOf(UnitController.class)).contains("");
        assertThat(Set.copyOf(patternsOf(TimeseriesController.class))).doesNotContain("/");
        assertThat(Set.copyOf(patternsOf(UnitController.class))).doesNotContain("/");
    }
}
