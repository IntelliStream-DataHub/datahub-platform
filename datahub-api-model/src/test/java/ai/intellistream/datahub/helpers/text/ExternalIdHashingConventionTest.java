// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fails the build if an external id is hashed without going through {@link ExternalIds#hash}.
 *
 * <p>The plan called for this explicitly, and the reason is that the failure mode is silent. A raw
 * {@code LongHashFunction.xx3().hashChars(externalId)} skips the lowercasing, so it either misses a
 * row that is there (lookup by an id whose case differs from the stored one) or lets a duplicate
 * past the unique index (two case-variants hashing differently). Neither throws. Both surface much
 * later as "the data is wrong", which is the most expensive kind of bug to trace back.
 *
 * <p>There are ~50 hashing sites across the platform and most of them are not external ids — stream
 * topics, roles and namespaces hash <em>names</em>, files hash <em>paths</em>. So the rule is
 * narrow: a raw hash whose argument mentions an external id is a violation, unless its file is in
 * {@link #EXEMPT}. Each exemption below is a deliberate decision, not a backlog.
 */
class ExternalIdHashingConventionTest {

    /**
     * A raw hash call. Matches both spellings in the codebase: the openhft builder and the
     * {@code IdGenerator.xxHash} wrapper around it.
     */
    private static final Pattern RAW_HASH = Pattern.compile(
            "(?:hashChars|xxHash)\\s*\\(([^;]*)");

    /** An argument that is an external id, however it is spelled at the call site. */
    private static final Pattern MENTIONS_EXTERNAL_ID = Pattern.compile("(?i)ext(ernal)?_?id");

    /**
     * Files allowed to hash their own external ids raw, each for a stated reason.
     *
     * <p>These are separate entity families with their own table, their own unique index and their
     * own normalisation, and they are out of scope for the verbatim-storage change. They are
     * internally consistent — each normalises before hashing and normalises again on lookup — so
     * leaving them alone is correct, while half-converting them would not be. Revisit deliberately
     * if any of them is ever brought under the same rule.
     */
    private static final Set<String> EXEMPT = Set.of(
            // Files and folders: their own inode table and index, still snake_cased on both sides.
            "INode.java",
            "FileController.java",
            "IINodeRepoImpl.java",
            // Same inode family: soft-delete rewrites the external id to a "DELETED_<checksum>_…"
            // tombstone and stores its hash. Not a user-supplied identifier at all by that point.
            "FileSystemService.java",
            // Change-data-capture integrations: the external id also seeds a replication publication
            // name and a topic prefix, which have charset rules of their own.
            "CDCIntegration.java",
            "CDCIntegrationForm.java",
            // Governance templates: separate table, separate index.
            "GovernanceTemplate.java",
            // Units are a fixed platform catalogue with snake_case ids we ship, not user-chosen
            // identity; UnitRepoImpl normalises on lookup to match.
            "UnitController.java",
            "UnitRepoImpl.java",
            // The helper itself, and the generic hashing utility it is built on.
            "ExternalIds.java",
            "IdGenerator.java",
            // PolicyType derives a synthetic constant id from the enum name, not a stored value.
            "PolicyType.java");

    @Test
    void noRawHashingOfExternalIds() {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (Path java : mainSources(root)) {
            if (EXEMPT.contains(java.getFileName().toString())) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(java);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isComment(line)) {
                    // Prose describing the rule is not a violation of it. Without this, the class
                    // that explains why raw hashing is wrong cannot say the words.
                    continue;
                }
                Matcher m = RAW_HASH.matcher(line);
                while (m.find()) {
                    if (MENTIONS_EXTERNAL_ID.matcher(m.group(1)).find()) {
                        violations.add(root.relativize(java) + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("External ids must be hashed with ExternalIds.hash(), which lowercases first so "
                    + "uniqueness and lookup stay case-insensitive. A raw hash here silently misses "
                    + "rows or lets duplicates past the unique index.\n"
                    + "Offending call sites:\n  " + String.join("\n  ", violations)
                    + "\n\nIf the value genuinely is not a node/event/subscription external id, add "
                    + "the file to ExternalIdHashingConventionTest.EXEMPT with a reason.");
        }
    }


    /**
     * Whether this line is comment or javadoc.
     *
     * <p>Line-level rather than a real parse, matching the rest of this scan: it looks at single
     * lines, so a hashing call is only ever missed if someone writes it after a {@code //} on the
     * same line, which is not a way anyone writes code. The cost of being wrong here is a missed
     * violation, and the guard is a backstop rather than the only thing standing between this rule
     * and the database.
     */
    private static boolean isComment(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }

    /** Guards the guard: if the scan finds nothing to look at, it proves nothing. */
    @Test
    void scanActuallyReachesTheSources() {
        List<Path> sources = mainSources(repositoryRoot());
        assertTrue(sources.size() > 100,
                "Expected to scan the platform's main sources, found only " + sources.size()
                        + ". The repository layout probably moved and this test is now vacuous.");
    }

    /**
     * Main <em>and</em> test sources.
     *
     * <p>Tests were exempt, and that exemption is how a violation reached production. A fixture
     * that derives a stored value itself, with a raw hash rather than through {@link ExternalIds},
     * agrees with any query that derives it the same way — so the test passes while production,
     * which lowercases, does something else. Two such fixtures existed, each passing only because
     * every identifier in them happened to be lowercase.
     *
     * <p>Asserting <em>about</em> the hash is still fine: the rule only fires on a raw hashing call
     * whose argument mentions an external id, and {@code ExternalIds.hash(...)} is never that.
     */
    private static List<Path> mainSources(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/")
                            || p.toString().contains("/src/test/java/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Walk up from the module's working directory to the Gradle root. */
    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Could not locate the repository root (no settings.gradle above "
                    + Path.of("").toAbsolutePath() + ")");
        }
        return dir;
    }
}
