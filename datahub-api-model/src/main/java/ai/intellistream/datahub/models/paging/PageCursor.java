// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.paging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Where a page stopped: the sort it was produced in, and the position of its last row.
 *
 * <h2>Why a cursor and not an offset</h2>
 * {@code OFFSET n} makes the database produce and discard n rows on every request, so the cost of a
 * page grows with how deep it is and the last page is the most expensive one. Worse, it is wrong
 * under concurrent writes: a row inserted before the current position shifts everything down, so
 * the next page repeats a row, and a deletion skips one. A cursor names a <em>position in the
 * order</em> rather than a count of rows, so the next page is a range scan the index can seek
 * straight to, and rows written elsewhere in the table cannot shift it.
 *
 * <h2>Why the sort is part of it</h2>
 * A position is only meaningful in the order that produced it. Paging by {@code eventTime} and then
 * switching to {@code type} mid-walk asks "everything after 14:32" of a sequence that is no longer
 * in time order — which returns a page that is silently wrong rather than one that fails. So the
 * sort travels inside the cursor, and the query layer refuses a request whose sort disagrees with
 * it instead of guessing which the caller meant.
 *
 * <h2>Why the id is part of it</h2>
 * The sort column alone is not a position unless it is unique. Rows sharing a timestamp straddle
 * the page boundary, and "everything after 14:32" then either repeats them or drops them depending
 * on which side the boundary fell. The id makes the order total, so there is exactly one row the
 * cursor can mean.
 *
 * <p>Opaque on purpose: base64 of a versioned, pipe-delimited form. Callers echo back what they
 * were handed rather than assembling one, which is what lets the encoding change — the {@code v1}
 * prefix is how a later format announces itself to an older reader.
 */
public record PageCursor(String property, boolean descending, String value, String id) {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    /** Tags a value segment that carries a value, as opposed to recording its absence. */
    private static final String VALUE_PREFIX = "v";
    /** The value segment for a row whose sort column is null. */
    private static final String NULL_VALUE = "n";
    /** How much of a caller-supplied fragment may appear in an error message. */
    private static final int MAX_ECHOED = 32;

    public PageCursor {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(id, "id");
        // value stays nullable on purpose: see the class note on null sort values.
    }

    /**
     * The opaque string handed to the caller as {@code nextCursor}.
     *
     * <p>Base64url without padding, so it survives a query string, a JSON body and a copy-paste
     * without escaping. The parts are joined rather than length-prefixed, and {@link #decode}
     * splits with a limit so a value containing the separator cannot forge extra fields.
     */
    public String encode() {
        // The value segment is tagged rather than merely present, so a row whose sort column is
        // null is a different cursor from one whose value is the empty string. They land in
        // different parts of the order, so conflating them would skip or repeat a whole block.
        String tagged = value == null ? NULL_VALUE : VALUE_PREFIX + value;
        String raw = String.join(SEPARATOR, VERSION, property, descending ? "desc" : "asc", id, tagged);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read a cursor. Null only when none was supplied.
     *
     * <p>A cursor that is present but unreadable throws. Returning null for it would hand back the
     * first page, and a client that pages by echoing what it was given would then loop on page one
     * indefinitely — never advancing, never finishing, never told anything is wrong. Guessing at
     * half a cursor is worse still: dropping the tie-breaker and keeping the timestamp silently
     * skips or repeats the rows around the boundary.
     *
     * <p>A cursor whose sort disagrees with the request is <em>not</em> handled here. That needs
     * the request's sort to detect, so the query layer rejects it.
     *
     * @throws MalformedCursorException when a cursor was supplied but cannot be read
     */
    public static PageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw malformed("it is not valid base64url");
        }
        // Limit 5: the value is last and may itself contain a separator.
        String[] parts = raw.split("\\" + SEPARATOR, 5);
        if (parts.length != 5) {
            throw malformed("it does not have the expected structure");
        }
        if (!VERSION.equals(parts[0])) {
            // A cursor minted by a newer format. Saying so beats decoding it wrongly.
            throw malformed("it was produced by an incompatible version (" + summarise(parts[0]) + ")");
        }
        String property = parts[1];
        String direction = parts[2];
        String id = parts[3];
        String tagged = parts[4];
        if (property.isBlank() || id.isBlank() || tagged.isEmpty()) {
            throw malformed("it is missing a property, id or value");
        }
        String value;
        if (NULL_VALUE.equals(tagged)) {
            value = null;
        } else if (tagged.startsWith(VALUE_PREFIX)) {
            value = tagged.substring(VALUE_PREFIX.length());
        } else {
            throw malformed("its value segment is not tagged");
        }
        return new PageCursor(property, "desc".equalsIgnoreCase(direction), value, id);
    }

    /**
     * A caller-supplied fragment, made safe to put in a message.
     *
     * <p>Everything in a cursor arrives base64-decoded, so it is arbitrary bytes of arbitrary
     * length. Echoed raw it would carry newlines into the log — where they forge entries — and
     * unbounded length into both the log and the response body. Only the version tag is echoed at
     * all, because naming the format a token came from is genuinely diagnostic; the rest is not
     * quoted back, since the caller already has the cursor.
     */
    private static String summarise(String fragment) {
        String stripped = fragment.replaceAll("[^\\p{Print}]", "?");
        return stripped.length() <= MAX_ECHOED ? stripped : stripped.substring(0, MAX_ECHOED) + "...";
    }

    private static MalformedCursorException malformed(String why) {
        return new MalformedCursorException(
                "The supplied cursor cannot be read because " + why
                        + ". Send back a nextCursor exactly as it was returned, or omit it to start again.");
    }

    /** Whether this cursor was produced by the given sort, and may therefore be continued by it. */
    public boolean matches(String sortProperty, boolean sortDescending) {
        return this.property.equals(sortProperty) && this.descending == sortDescending;
    }
}
