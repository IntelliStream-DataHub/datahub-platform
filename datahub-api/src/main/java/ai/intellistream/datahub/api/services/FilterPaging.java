// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.repositories.node.NodePredicateBuilder;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.repositories.subscription.SubscriptionPredicateBuilder;
import ai.intellistream.datahub.repositories.subscription.SubscriptionSort;

import java.util.List;
import java.util.function.Predicate;

/**
 * Keyset paging, shared by the JPA-backed filter endpoints — the three node ones and
 * {@code /subscriptions/filter}.
 *
 * <p>Here rather than per service because the two rules worth getting right are the same for all of
 * them, and neither fails loudly when it is wrong: continuing a cursor under a different sort
 * returns a page that is silently missing rows, and a cursor built from the wrong row quietly ends
 * the walk. The events path answers the same questions against ClickHouse, which cannot share this
 * code, so {@code EventService} states them again — {@code PageCursor} is what keeps the two
 * honest.
 *
 * <p>It was {@code NodePaging} while nodes were the only things it paged. Subscriptions are not
 * nodes but page identically, so the name went with the job rather than with the first caller.
 */
final class FilterPaging {

    private FilterPaging() {
    }

    /**
     * The cursor to continue from, or null when there is none.
     *
     * <p>Every rejection is a {@link MalformedCursorException}, left to travel to its advice. It
     * used to be translated into a {@code BadRequestException} here, which bought nothing: the
     * condition already had a type saying what happened, and flattening it meant the advice could
     * no longer tell a stale cursor from any other bad request.
     *
     * @throws MalformedCursorException when the cursor cannot be read, or was produced by a
     *                                  different sort than the one requested. A cursor is a
     *                                  position in one particular order, so continuing it under
     *                                  another asks "everything after X" of a sequence no longer
     *                                  in that order — a page that looks fine and is not.
     */
    static PageCursor validated(String rawCursor, NodeSort sort) {
        return validated(rawCursor, sort.property(), sort.descending(), sort::canReadBoundary);
    }

    /** @see #validated(String, NodeSort) */
    static PageCursor validated(String rawCursor, SubscriptionSort sort) {
        return validated(rawCursor, sort.property(), sort.descending(), sort::canReadBoundary);
    }

    private static PageCursor validated(String rawCursor, String property, boolean descending,
                                        Predicate<String> canReadBoundary) {
        PageCursor cursor = PageCursor.decode(rawCursor);
        if (cursor == null) {
            return null; // none supplied: the start of a walk, not an error in one
        }
        if (!canReadBoundary.test(cursor.value())) {
            // Well-formed encoding, unusable contents — a forged or truncated cursor. Rejected like
            // any other unreadable one; letting the parse fail downstream was a caller-triggered
            // 500, and ignoring it would loop a paging client on the first page forever.
            // The value is not quoted back. It arrives base64-decoded, so it can carry any bytes
            // at any length: echoing it puts caller-controlled text into the response body and the
            // log line, where newlines forge log entries and megabytes cost real money. The caller
            // already holds the cursor, so naming the field it failed on tells them everything
            // quoting it would.
            throw new MalformedCursorException(
                    "The cursor's position cannot be read as a %s. ".formatted(property)
                    + "Send back a nextCursor exactly as it was returned, or omit it to start again.");
        }
        if (!cursor.matches(property, descending)) {
            throw new MalformedCursorException(
                    "This cursor was produced by a different sort (%s %s) than the one requested (%s %s). "
                            .formatted(cursor.property(), cursor.descending() ? "desc" : "asc",
                                    property, descending ? "desc" : "asc")
                            + "Send the cursor with the sort it came from, or start a new walk without it.");
        }
        return cursor;
    }

    /**
     * The cursor for the page after this one, or null when there is not one.
     *
     * <p>A short page means the end of the results, so no cursor: "keep going while nextCursor is
     * present" is then the whole client loop, with no separate end-of-data signal to get wrong. A
     * full page may still be the last, in which case the caller makes one extra request that comes
     * back empty — the price of not counting the rows twice.
     *
     * <p>A null value is a legitimate cursor here, unlike an absent one: a sort column may be null,
     * and the row that ended the page may be one of those. {@link PageCursor} encodes the
     * difference.
     */
    static String nextCursor(List<? extends NodeEntity> page, int limit, NodeSort sort) {
        if (isLastPage(page, limit)) {
            return null;
        }
        NodeEntity last = page.get(page.size() - 1);
        return new PageCursor(sort.property(), sort.descending(),
                NodePredicateBuilder.cursorValue(last, sort), String.valueOf(last.getId())).encode();
    }

    /** @see #nextCursor(List, int, NodeSort) */
    static String nextCursor(List<SubscriptionEntity> page, int limit, SubscriptionSort sort) {
        if (isLastPage(page, limit)) {
            return null;
        }
        SubscriptionEntity last = page.get(page.size() - 1);
        return new PageCursor(sort.property(), sort.descending(),
                SubscriptionPredicateBuilder.cursorValue(last, sort), String.valueOf(last.getId())).encode();
    }

    private static boolean isLastPage(List<?> page, int limit) {
        return page.isEmpty() || page.size() < limit;
    }
}
