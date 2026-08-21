// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.Getter;

import java.util.List;
import java.util.Set;

/**
 * Thrown when a request body names fields the target type does not declare.
 *
 * <p>Carries every offender found in the body rather than the first, each located by an RFC 6901
 * JSON Pointer, so a caller with several stale fields learns about them in one response instead of
 * one per round trip.
 */
@Getter
public class UnknownRequestFieldsException extends RuntimeException {

    /** One unknown field: where it sat, and what that position accepts. */
    public record UnknownField(String pointer, Set<String> allowed) {

        public UnknownField {
            allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
        }

        /** The trailing segment, for a message that reads as the caller wrote it. */
        public String name() {
            return pointer.substring(pointer.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
        }
    }

    private final List<UnknownField> unknownFields;

    public UnknownRequestFieldsException(List<UnknownField> unknownFields) {
        super(summary(unknownFields));
        this.unknownFields = List.copyOf(unknownFields);
    }

    private static String summary(List<UnknownField> fields) {
        return fields.size() == 1
                ? "Unknown field: " + fields.getFirst().name()
                : "Unknown fields: " + String.join(", ", fields.stream().map(UnknownField::name).toList());
    }
}
