// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Small filesystem helpers shared by the cleanup tasks. */
final class FileCleanupOps {

    private FileCleanupOps() {
    }

    /**
     * Recursively deletes {@code path} (a file or a directory tree). Children are deleted before
     * their parents (reverse-depth order). No-op if the path does not exist.
     */
    static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            // Sort deepest-first so directories are emptied before they are removed.
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
