// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import java.util.List;

/**
 * A timestamp in neither accepted form, raised from a service rather than from binding.
 *
 * <p>Most timestamps reach a typed field and are parsed by {@code TimestampDeserializer}, so a bad
 * one surfaces during binding and {@code UnreadableRequestBodyExceptionHandler} answers it. The two
 * that do not are the ones this exists for: a datapoint's {@code timestamp} and the datapoint
 * delete window's bounds are plain strings on the wire, bind cleanly, and are only parsed once a
 * service looks at them.
 *
 * <p>All three answer the same 422, which is the point. Before, the same mistyped timestamp was a
 * 400 through binding, a 400 with a {@code fields} body on a delete, and a 500 on an insert — three
 * answers to one error, decided by which field the caller happened to put it in.
 *
 * @see InvalidTimestampExceptionHandler
 * @see Problems#invalidTimestamp(String, String, java.util.Collection)
 */
public class InvalidTimestampException extends RuntimeException {

    /** Named locators, so the caller can tell which bound of which series was wrong. */
    private final transient List<Problems.FieldProblem> fields;

    public InvalidTimestampException(String message, FieldErrors fields) {
        super(message);
        this.fields = fields == null ? List.of() : fields.asList();
    }

    public List<Problems.FieldProblem> getFields() {
        return fields;
    }
}
