// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.paging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cursor has to survive a round trip through a client that treats it as an opaque string, and
 * has to refuse to half-decode. A cursor that parses into something slightly wrong produces a page
 * that is silently missing rows, which is the failure this type exists to prevent.
 */
class PageCursorTest {

    @Test
    void roundTripsThroughItsEncoding() {
        PageCursor original = new PageCursor("eventTime", true, "1745241600000",
                "0193a4b5-6c7d-7e8f-9012-3456789ab001");

        PageCursor decoded = PageCursor.decode(original.encode());

        assertEquals(original, decoded);
    }

    @Test
    void isOpaqueRatherThanReadable() {
        String encoded = new PageCursor("eventTime", false, "1745241600000", "abc").encode();

        // Not a contract, but the point of encoding: a caller cannot pick the cursor apart and
        // rebuild it by hand, so the format stays ours to change.
        assertFalse(encoded.contains("eventTime"));
        assertFalse(encoded.contains("|"));
    }

    /** A value containing the separator must not be able to forge extra fields. */
    @Test
    void aSeparatorInsideTheValueIsNotStructural() {
        PageCursor original = new PageCursor("externalId", false, "work|order|4711", "id-1");

        PageCursor decoded = PageCursor.decode(original.encode());

        assertEquals("work|order|4711", decoded.value());
        assertEquals("id-1", decoded.id());
    }

    /**
     * A supplied cursor that cannot be read is an error, not a reason to start over. Returning the
     * first page instead would loop a client that pages by echoing what it was given: page one
     * forever, no advance, no completion, no error.
     */
    @ParameterizedTest
    @ValueSource(strings = {"not-base64!!", "YWJj", "%%%"})
    void anUnreadableCursorThrows(String bad) {
        assertThrows(MalformedCursorException.class, () -> PageCursor.decode(bad));
    }

    /** Absent is not malformed: no cursor means the first page, which is what it should mean. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anAbsentCursorIsNotAnError(String blank) {
        assertNull(PageCursor.decode(blank));
    }

    @Test
    void nullDecodesToNull() {
        assertNull(PageCursor.decode(null));
    }

    @Test
    void aCursorFromAnotherVersionIsRejected() {
        String future = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v2|eventTime|asc|id-1|123".getBytes(StandardCharsets.UTF_8));

        // Named in the message rather than decoded on a guess about what v2 means.
        assertTrue(assertThrows(MalformedCursorException.class, () -> PageCursor.decode(future))
                .getMessage().contains("v2"));
    }

    @Test
    void matchesOnlyTheSortThatProducedIt() {
        PageCursor cursor = new PageCursor("eventTime", false, "123", "id-1");

        assertTrue(cursor.matches("eventTime", false));
        assertFalse(cursor.matches("eventTime", true), "same column, opposite direction");
        assertFalse(cursor.matches("createdTime", false), "different column");
    }


    // --- what an error message is allowed to say back ------------------------------------------
    // Everything in a cursor arrives base64-decoded, so it is arbitrary bytes of arbitrary length.
    // A message that quotes it puts caller-controlled text into the response body and the log line,
    // where newlines forge log entries and length costs real money.

    @Test
    void anIncompatibleVersionIsEchoedButStrippedOfControlCharacters() {
        String nasty = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v2\nFAKE LOG LINE|name|asc|1|vx".getBytes(StandardCharsets.UTF_8));

        String message = assertThrows(MalformedCursorException.class,
                () -> PageCursor.decode(nasty)).getMessage();

        assertFalse(message.contains("\n"), "a newline would forge a log entry: " + message);
        assertTrue(message.contains("?"), "control characters are replaced, not dropped silently");
    }

    @Test
    void anAbsurdVersionTagIsTruncated() {
        String huge = "v" + "A".repeat(5_000);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (huge + "|name|asc|1|vx").getBytes(StandardCharsets.UTF_8));

        String message = assertThrows(MalformedCursorException.class,
                () -> PageCursor.decode(encoded)).getMessage();

        assertTrue(message.length() < 300, "message grew with the input: " + message.length() + " chars");
        assertTrue(message.contains("..."), "truncation should be visible, not silent");
    }

    /** The boundary value is never quoted back at all — the caller already holds the cursor. */
    @Test
    void aReadableCursorsValueNeverAppearsInAnyMessage() {
        String secretish = "AAAA-carries-whatever-the-caller-put-here";
        String encoded = new PageCursor("name", false, secretish, "1").encode();

        // Decodes fine, so no message exists to leak into; the service-raised messages that reject
        // such a cursor name only the field, which NodePagingMessageTest covers.
        assertEquals(secretish, PageCursor.decode(encoded).value());
    }
}
