// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.paging;

/**
 * A cursor was supplied but cannot be used.
 *
 * <p>Thrown rather than swallowed, because the alternative is worse than it looks. Ignoring a bad
 * cursor returns the <em>first</em> page — and a client paging in a loop, sending back whatever it
 * was given, then receives page one forever: it never advances, never finishes, and never sees an
 * error. A 400 stops it at the first bad request and says which field is wrong.
 *
 * <p>An <em>absent</em> cursor is not this. Asking for the first page is exactly what no cursor
 * means, so null and blank are the start of a walk, not an error in one.
 */
public class MalformedCursorException extends IllegalArgumentException {

    public MalformedCursorException(String message) {
        super(message);
    }
}
