// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The child-process contract, exercised with stub converters so the cases that matter can be
 * tested without the native binary, plus one run against the real one when it has been built.
 */
class RvmConverterTest {

    @TempDir
    Path dir;

    /** The binary tools/rvm-converter/build.sh produces, if this checkout has built it. */
    private static final Path REAL_BINARY =
            Path.of("..", "tools", "rvm-converter", "build", "rvm-converter");

    @Test
    void readsTheModelFromStdoutAndTheLogFromStderr() throws Exception {
        Path stub = stub("""
                echo "[I] Tessellated 3 items" >&2
                printf 'glTF\\x02\\x00\\x00\\x00modeldata'
                """);

        RvmConversion result = new RvmConverter(stub, Duration.ofSeconds(30))
                .convert(dir.resolve("model.rvm"), null);

        assertEquals("glTF", new String(result.glb(), 0, 4, StandardCharsets.UTF_8));
        assertTrue(result.log().contains("Tessellated 3 items"), result.log());
    }

    @Test
    void passesTheAttributeFileWhenThereIsOne() throws Exception {
        // The stub echoes its own arguments, so the test can see what the converter was told.
        Path stub = stub("""
                echo "$@" >&2
                printf 'glTF\\x02\\x00\\x00\\x00x'
                """);

        RvmConversion with = new RvmConverter(stub, Duration.ofSeconds(30))
                .convert(dir.resolve("m.rvm"), dir.resolve("m.txt"));
        assertTrue(with.log().contains("m.txt"), with.log());

        RvmConversion without = new RvmConverter(stub, Duration.ofSeconds(30))
                .convert(dir.resolve("m.rvm"), null);
        assertTrue(without.log().contains("m.rvm"), without.log());
        assertTrue(!without.log().contains("m.txt"), without.log());
    }

    /**
     * The reason stderr is drained on its own thread. This stub fills the stderr pipe buffer before
     * writing a byte of model, so a converter that read stdout to completion first would wait on a
     * child that is itself waiting for someone to read its log.
     */
    @Test
    void doesNotDeadlockWhenTheConverterIsChattyBeforeItWrites() throws Exception {
        Path stub = stub("""
                yes "[I] a log line long enough to fill a pipe buffer reasonably quickly" \
                    | head -40000 >&2
                printf 'glTF\\x02\\x00\\x00\\x00modeldata'
                """);

        RvmConversion result = new RvmConverter(stub, Duration.ofSeconds(60))
                .convert(dir.resolve("model.rvm"), null);

        assertEquals("glTF", new String(result.glb(), 0, 4, StandardCharsets.UTF_8));
        assertEquals(40000, result.log().lines().count());
    }

    @Test
    void failsWhenTheConverterExitsNonZero() throws Exception {
        Path stub = stub("""
                echo "[E] Failed to parse model.rvm: bad chunk" >&2
                exit 3
                """);

        RvmConversionException e = assertThrows(RvmConversionException.class,
                () -> new RvmConverter(stub, Duration.ofSeconds(30))
                        .convert(dir.resolve("model.rvm"), null));

        assertTrue(e.getMessage().contains("exited 3"), e.getMessage());
        // The reason has to survive into the message, or the failure is unactionable.
        assertTrue(e.getMessage().contains("bad chunk"), e.getMessage());
    }

    @Test
    void failsWhenTheOutputIsNotAModel() throws Exception {
        // Exit 0 but a diagnostic where the model should be. Storing that would produce a file
        // that only fails when somebody opens it.
        Path stub = stub("""
                echo "usage: rvm-converter [options] files" >&2
                echo "not a model at all"
                """);

        RvmConversionException e = assertThrows(RvmConversionException.class,
                () -> new RvmConverter(stub, Duration.ofSeconds(30))
                        .convert(dir.resolve("model.rvm"), null));

        assertTrue(e.getMessage().contains("not a GLB"), e.getMessage());
    }

    @Test
    void failsWhenTheConverterHangs() throws Exception {
        Path stub = stub("sleep 30\n");

        RvmConversionException e = assertThrows(RvmConversionException.class,
                () -> new RvmConverter(stub, Duration.ofMillis(400))
                        .convert(dir.resolve("model.rvm"), null));

        assertTrue(e.getMessage().contains("longer than"), e.getMessage());
    }

    @Test
    void failsClearlyWhenTheBinaryIsMissing() {
        RvmConversionException e = assertThrows(RvmConversionException.class,
                () -> new RvmConverter(dir.resolve("absent"), Duration.ofSeconds(5))
                        .convert(dir.resolve("model.rvm"), null));

        assertTrue(e.getMessage().contains("build.sh"), e.getMessage());
    }

    /** Runs the real converter over the committed fixture, when the binary has been built. */
    @Test
    void convertsARealRvmWhenTheBinaryIsBuilt() throws Exception {
        assumeTrue(Files.isExecutable(REAL_BINARY),
                "no converter binary; build it with tools/rvm-converter/build.sh");

        Path rvm = dir.resolve("box.rvm");
        try (var in = getClass().getResourceAsStream("/box.rvm")) {
            assertNotNull(in, "missing test fixture box.rvm");
            Files.write(rvm, in.readAllBytes());
        }

        RvmConversion result = new RvmConverter(REAL_BINARY, Duration.ofMinutes(2))
                .convert(rvm, null);

        assertEquals("glTF", new String(result.glb(), 0, 4, StandardCharsets.UTF_8));
        assertTrue(result.glb().length > 512, "suspiciously small model: " + result.glb().length);
        assertTrue(result.log().contains("Successfully parsed"), result.log());
    }

    private Path stub(String body) throws IOException {
        Path script = dir.resolve("stub-converter-" + body.hashCode() + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }
}
