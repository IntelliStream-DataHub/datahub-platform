// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every source file states its licence, and states the right one.
 *
 * <p>The platform is AGPL-3.0-or-later, with two exceptions: {@code datahub-api-model} and
 * {@code datahub-java-sdk} are Apache-2.0 because they are linked into other people's applications,
 * which copyleft would prevent. A file carrying the wrong header — or none — is the kind of thing
 * nobody notices until it matters, and by then it has been shipped.
 *
 * <p>Twelve files had no header at all: the whole graph-transfer feature plus the user-info
 * rejection handler. Each was added alongside correct neighbours, which is exactly why review
 * missed them.
 *
 * <p>Walks the repository from this module's parent rather than the classpath, so it covers every
 * module including ones with no tests of their own.
 */
class LicenceHeaderTest {

    private static final String AGPL = "// SPDX-License-Identifier: AGPL-3.0-or-later";
    private static final String APACHE = "// SPDX-License-Identifier: Apache-2.0";

    /** Linked into other people's applications, so deliberately not copyleft. */
    private static final Set<String> APACHE_MODULES = Set.of("datahub-api-model", "datahub-java-sdk");

    private static Path repositoryRoot() {
        // Gradle runs tests with the module directory as the working directory.
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertThat(Files.isDirectory(root.resolve("datahub-api")))
                .as("expected the repository root at %s; this test walks the source tree and must "
                        + "run with the module directory as its working directory", root)
                .isTrue();
        return root;
    }

    private record SourceFile(Path path, String module, String firstLine) {}

    private static List<SourceFile> allJavaSources() throws IOException {
        Path root = repositoryRoot();
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .toList()) {
                Path relative = root.relativize(p);
                String module = relative.getName(0).toString();
                String first;
                try (var lines = Files.lines(p)) {
                    first = lines.findFirst().orElse("");
                }
                files.add(new SourceFile(relative, module, first));
            }
        }
        return files;
    }

    @Test
    @DisplayName("the walk actually finds the source tree")
    void theWalkFindsSources() throws IOException {
        // A guard on the guard: a walk that found nothing would pass every assertion below.
        assertThat(allJavaSources()).hasSizeGreaterThan(500);
    }

    @Test
    @DisplayName("every source file carries the licence its module requires")
    void everySourceFileCarriesTheRightLicence() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (SourceFile file : allJavaSources()) {
            String expected = APACHE_MODULES.contains(file.module()) ? APACHE : AGPL;
            if (!expected.equals(file.firstLine())) {
                wrong.add(file.path() + " -> expected \"" + expected + "\", found \""
                        + file.firstLine() + "\"");
            }
        }
        assertThat(wrong)
                .as("AGPL-3.0-or-later everywhere except %s, which are Apache-2.0 because they are "
                        + "linked into other people's applications: %s", APACHE_MODULES, wrong)
                .isEmpty();
    }
}
