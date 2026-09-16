// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

import java.util.Arrays;

/**
 * Stands in for the rvm-converter binary in {@link RvmConverterTest}, run as a real child process.
 *
 * <p>Java rather than shell scripts, so the stub behaves the same on every OS the build runs on:
 * {@code printf} escapes differ between the {@code sh} of macOS and of Linux, and Windows has no
 * {@code sh} at all. The first argument picks the behaviour; the rest are what the converter passed.
 */
final class StubConverter {

    /** A GLB header (magic, version 2) and a few bytes of body. */
    static final byte[] MODEL = {'g', 'l', 'T', 'F', 2, 0, 0, 0, 'm', 'o', 'd', 'e', 'l', 'd', 'a', 't', 'a'};

    static final int CHATTY_LINES = 40_000;

    public static void main(String[] args) throws InterruptedException {
        String[] passed = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "model" -> {
                System.err.println("[I] Tessellated 3 items");
                writeModel();
            }
            case "echo-args" -> {
                System.err.println(String.join(" ", passed));
                writeModel();
            }
            case "chatty" -> {
                for (int i = 0; i < CHATTY_LINES; i++) {
                    System.err.println("[I] a log line long enough to fill a pipe buffer reasonably quickly");
                }
                writeModel();
            }
            case "fail" -> {
                System.err.println("[E] Failed to parse model.rvm: bad chunk");
                System.exit(3);
            }
            case "not-a-model" -> {
                System.err.println("usage: rvm-converter [options] files");
                System.out.println("not a model at all");
            }
            case "hang" -> Thread.sleep(30_000);
            default -> throw new IllegalArgumentException("Unknown stub mode " + args[0]);
        }
    }

    private static void writeModel() {
        System.out.writeBytes(MODEL);
        System.out.flush();
    }
}
