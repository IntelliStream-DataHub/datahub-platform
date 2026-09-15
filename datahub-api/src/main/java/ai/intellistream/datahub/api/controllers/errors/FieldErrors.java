// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the fields a request got wrong, so a caller learns about all of them at once.
 *
 * <p>Validation here runs over the whole batch before anything is written, and this is the half of
 * that promise the caller sees: submitting fifty items and being told about the first mistake only
 * turns one round trip into fifty. Every accumulating throw site in the API used to do this by
 * hand, mutating a {@code BadRequestError} nested inside a {@code ResponseError} — a wrapper whose
 * only job was to be unwrapped again by the advice.
 *
 * <p>Entries become {@link Problems.FieldProblem}s, so they land on the wire in the same
 * {@code fields} shape as a bean-validation failure. A caller correcting their request should not
 * have to care whether a rule ran in a validator or in a hand-written check.
 */
public final class FieldErrors {

    private final List<Problems.FieldProblem> entries = new ArrayList<>();

    /** One rejected field. Chainable, because most sites add several in a row. */
    public FieldErrors addFieldError(String field, String message) {
        entries.add(new Problems.FieldProblem(field, message, null, null));
        return this;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<Problems.FieldProblem> asList() {
        return List.copyOf(entries);
    }
}
