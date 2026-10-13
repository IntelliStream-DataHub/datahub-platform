// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

/**
 * A processor could not do its work. Thrown from {@link Processor#onTrigger}, it discards the
 * invocation; the engine retries it as the processor's {@code retry} setting allows, and fails the
 * run after that. Unchecked, so that a property conversion can throw it from anywhere.
 */
public class ProcessException extends RuntimeException {

    public ProcessException(String message) {
        super(message);
    }

    public ProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
