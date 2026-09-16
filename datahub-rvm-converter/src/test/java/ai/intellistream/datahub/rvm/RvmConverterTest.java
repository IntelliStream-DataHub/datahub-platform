// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
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

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @Test
    void readsTheModelFromStdoutAndTheLogFromStderr() throws Exception {
        RvmConversion result = new RvmConverter(stub("model"), Duration.ofSeconds(30))
                .convert(dir.resolve("model.rvm"), null);

        assertArrayEquals(StubConverter.MODEL, result.glb());
        assertTrue(result.log().contains("Tessellated 3 items"), result.log());
    }

    @Test
    void passesTheAttributeFileWhenThereIsOne() throws Exception {
        // The stub echoes its own arguments, so the test can see what the converter was told.
        Path stub = stub("echo-args");

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
        RvmConversion result = new RvmConverter(stub("chatty"), Duration.ofSeconds(60))
                .convert(dir.resolve("model.rvm"), null);

        assertArrayEquals(StubConverter.MODEL, result.glb());
        assertEquals(StubConverter.CHATTY_LINES, result.log().lines().count());
    }

    @Test
    void failsWhenTheConverterExitsNonZero() throws Exception {
        Path stub = stub("fail");

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
        Path stub = stub("not-a-model");

        RvmConversionException e = assertThrows(RvmConversionException.class,
                () -> new RvmConverter(stub, Duration.ofSeconds(30))
                        .convert(dir.resolve("model.rvm"), null));

        assertTrue(e.getMessage().contains("not a GLB"), e.getMessage());
    }

    /**
     * The stub sleeps for 30 seconds, so returning well inside that shows the timeout bounds the
     * call. On Windows the stub runs under cmd.exe, and a child that outlived it would hold the
     * pipes open until it finished.
     */
    @Test
    void failsWhenTheConverterHangs() throws Exception {
        Path stub = stub("hang");

        RvmConversionException e = assertTimeout(Duration.ofSeconds(15), () -> assertThrows(
                RvmConversionException.class,
                () -> new RvmConverter(stub, Duration.ofMillis(400))
                        .convert(dir.resolve("model.rvm"), null)));

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

    /**
     * A launcher that runs {@link StubConverter} in {@code mode}. The converter runs a single
     * executable, and the JVM's own arguments have to come before the converter's, hence a script.
     * It clears the JVM option variables because the JVM reports them on stderr, which is the log.
     */
    private Path stub(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        Path classes = Path.of(StubConverter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String main = StubConverter.class.getName();

        if (WINDOWS) {
            Path script = dir.resolve("stub-" + mode + ".cmd");
            Files.writeString(script, String.join("\r\n",
                    "@echo off",
                    "set \"JAVA_TOOL_OPTIONS=\"",
                    "set \"JDK_JAVA_OPTIONS=\"",
                    "set \"_JAVA_OPTIONS=\"",
                    "\"" + java + "\" -cp \"" + classes + "\" " + main + " " + mode + " %*",
                    ""));
            return script;
        }
        Path script = dir.resolve("stub-" + mode + ".sh");
        Files.writeString(script, String.join("\n",
                "#!/bin/sh",
                "unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS",
                "exec " + shQuote(java) + " -cp " + shQuote(classes) + " " + main + " " + mode + " \"$@\"",
                ""));
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static String shQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }
}
