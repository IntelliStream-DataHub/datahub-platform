// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Converts an AVEVA PDMS/E3D RVM model to glTF by running the {@code rvm-converter} binary, which
 * writes the GLB to its stdout. See {@code tools/rvm-converter} for the binary and how to build it.
 */
public final class RvmConverter {

    private final Path executable;
    private final Duration timeout;

    public RvmConverter(Path executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    /**
     * Convert {@code rvm}, optionally with the attribute sidecar that carries the tags.
     *
     * <p>Pass the attribute file whenever there is one: without it the geometry still converts, but
     * every node loses the tag, discipline and material that make the model worth linking to
     * anything.
     *
     * @throws RvmConversionException if the converter fails, times out, or produces no model
     */
    public RvmConversion convert(Path rvm, Path attributes) throws RvmConversionException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--output-gltf=-"));
        command.add(rvm.toString());
        if (attributes != null) {
            command.add(attributes.toString());
        }

        Process process = start(command);
        // Both streams are drained off-thread, for two separate reasons. The converter is chatty on
        // stderr, tens of lines for a trivial model and far more for a real one, so reading only
        // stdout would fill the stderr pipe buffer and leave both sides waiting on each other. And
        // reading either one on this thread would block past the timeout, which is what has to
        // bound a converter that never finishes.
        StringBuilder log = new StringBuilder();
        ByteArrayOutputStream model = new ByteArrayOutputStream();
        Thread out = Thread.ofVirtual().start(() -> copy(process.getInputStream(), model));
        Thread err = Thread.ofVirtual().start(() -> readInto(process.getErrorStream(), log));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            // Either way the streams end, so the readers finish and nothing is left running.
            out.join();
            err.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RvmConversionException("Interrupted while converting " + rvm, e);
        }

        if (!finished) {
            throw new RvmConversionException("Converting " + rvm + " took longer than " + timeout);
        }
        byte[] glb = model.toByteArray();
        if (process.exitValue() != 0) {
            throw new RvmConversionException(
                    "Converter exited " + process.exitValue() + " for " + rvm + ": " + tail(log));
        }
        if (!isGlb(glb)) {
            // A converter that writes a diagnostic where the model should be would otherwise be
            // stored as a corrupt file and only noticed by whoever tried to open it.
            throw new RvmConversionException(
                    "Converter produced " + glb.length + " bytes that are not a GLB for " + rvm
                            + ": " + tail(log));
        }
        return new RvmConversion(glb, log.toString());
    }

    private Process start(List<String> command) throws RvmConversionException {
        if (!Files.isExecutable(executable)) {
            throw new RvmConversionException("No converter binary at " + executable
                    + "; build it with tools/rvm-converter/build.sh");
        }
        try {
            // Never redirectErrorStream: the log would be interleaved into the GLB.
            return new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new RvmConversionException("Could not run " + executable, e);
        }
    }

    private static void copy(InputStream stream, ByteArrayOutputStream sink) {
        try (stream) {
            stream.transferTo(sink);
        } catch (IOException e) {
            // A killed process closes the pipe mid-read; the timeout is what the caller hears about.
            throw new UncheckedIOException(e);
        }
    }

    private static void readInto(InputStream stream, StringBuilder sink) {
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** glTF binary starts with the magic "glTF". */
    private static boolean isGlb(byte[] bytes) {
        return bytes.length > 12
                && bytes[0] == 'g' && bytes[1] == 'l' && bytes[2] == 'T' && bytes[3] == 'F';
    }

    /** The last few log lines, which is where the converter puts the reason it gave up. */
    private static String tail(StringBuilder log) {
        String[] lines = log.toString().split("\n");
        int from = Math.max(0, lines.length - 5);
        return String.join(" | ", List.of(lines).subList(from, lines.length));
    }
}
