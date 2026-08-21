// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatahubConsoleApplication} does not use the default whole-base-package scan — it lists
 * base packages explicitly. A new package of {@code @Component}s is therefore invisible until it is
 * added to that list, and with {@code spring.main.lazy-initialization=true} the failure surfaces as
 * an {@code UnsatisfiedDependencyException} on the first request rather than at startup.
 *
 * <p>This test closes that gap: every Spring stereotype under {@code ai.intellistream.dhconsole}
 * must fall under one of the declared base packages.
 */
class ComponentScanCoverageTest {

    private static final String ROOT = "ai.intellistream.dhconsole";

    @Test
    void everyComponentUnderTheConsolePackageIsActuallyScanned() {
        List<String> basePackages = Arrays.stream(
                        DatahubConsoleApplication.class.getAnnotation(ComponentScan.class).basePackages())
                .filter(p -> p.startsWith(ROOT))
                .toList();

        // Conditional components (e.g. the chat beans) are only candidates when their property is
        // set, so scan with an environment that satisfies them.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("datahub.chat.enabled", "true");
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.setEnvironment(environment);

        Set<String> unscanned = scanner.findCandidateComponents(ROOT).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> name != null)
                // The application class itself is the configuration root, not a scanned candidate.
                .filter(name -> !name.equals(DatahubConsoleApplication.class.getName()))
                .filter(name -> basePackages.stream().noneMatch(
                        base -> name.startsWith(base + ".")))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

        assertThat(unscanned)
                .as("these classes are Spring components but sit outside @ComponentScan(basePackages) "
                        + "on DatahubConsoleApplication, so no bean is created for them")
                .isEmpty();
    }
}
