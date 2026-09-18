// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.repositories.subscription.SubscriptionSort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A cursor's id is caller input too, and an unusable one is a 400.
 *
 * <p>Both node and subscription keysets read the tie-breaker with {@code Long.valueOf(cursor.id())},
 * which threw a {@code NumberFormatException} out of the query builder on anything else — a 500
 * produced by a value the caller supplied. The boundary <em>value</em> was already guarded for
 * exactly that reason ({@code canReadBoundary}); the id was missed, because the server only ever
 * mints numeric ids on these paths and so no legitimate client reaches it.
 *
 * <p>That is not the same as unreachable. A cursor is opaque but <em>not signed</em> — anything can
 * arrive in one — and an event cursor's id is a UUID, so pasting one into a node filter produces a
 * non-numeric id without anyone having to forge a token by hand.
 */
class FilterPagingCursorIdTest {

    private static final NodeSort NODE_SORT = NodeSort.DEFAULT;
    private static final SubscriptionSort SUBSCRIPTION_SORT = SubscriptionSort.DEFAULT;

    private static String cursorWithId(String id) {
        return new PageCursor(NODE_SORT.property(), NODE_SORT.descending(), "1776868253563456", id)
                .encode();
    }

    @Test
    void aNonNumericIdIsRejectedOnANodeFilter() {
        assertThrows(MalformedCursorException.class,
                () -> FilterPaging.validated(cursorWithId("not-a-number"), NODE_SORT));
    }

    /** The shape that arrives without anyone hand-editing a token: an event cursor's UUID. */
    @Test
    void anEventCursorsUuidIdIsRejectedRatherThanThrowingFromTheQueryBuilder() {
        assertThrows(MalformedCursorException.class,
                () -> FilterPaging.validated(
                        cursorWithId("0b5fdd3e-6d05-4f7a-9d3e-0f0a2a1b7c44"), NODE_SORT));
    }

    @Test
    void aNonNumericIdIsRejectedOnASubscriptionFilter() {
        String cursor = new PageCursor(SUBSCRIPTION_SORT.property(), SUBSCRIPTION_SORT.descending(),
                "1776868253563456", "not-a-number").encode();

        assertThrows(MalformedCursorException.class,
                () -> FilterPaging.validated(cursor, SUBSCRIPTION_SORT));
    }

    /** The guard rejects a shape, not every cursor: the ones the server mints still pass. */
    @Test
    void aNumericIdIsAccepted() {
        PageCursor cursor = assertDoesNotThrow(
                () -> FilterPaging.validated(cursorWithId("4211"), NODE_SORT));

        assertNotNull(cursor);
    }

    /** No cursor is the start of a walk, not a malformed one — the guard must not change that. */
    @Test
    void anAbsentCursorIsStillNotAnError() {
        assertDoesNotThrow(() -> FilterPaging.validated(null, NODE_SORT));
    }
}
