// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A delete the API refuses because something still depends on what is being deleted.
 *
 * <p>Two guards raise it, and they want different things from the caller: a timeseries still
 * referenced by a subscription needs the subscription removed first, while a selection that would
 * cut nodes off from the graph root needs those nodes included in the deletion instead. They carry
 * different {@link #getType() type} URIs so a client can tell them apart without reading prose.
 *
 * <p>It used to carry a {@code ResponseError<BadRequestError>} that seven controllers each caught
 * and returned directly, which made it the one exception in the API still answering in the old
 * envelope. It now carries only the facts, and {@link ResourceDeleteExceptionHandler} renders them.
 */
public class ResourceDeleteException extends RuntimeException {

    private final URI type;

    // transient: rebuilt per request from the throw site and never Java-serialized with the throwable.
    private final transient List<Map<String, String>> blockedBy;

    public ResourceDeleteException(URI type, String detail, List<Map<String, String>> blockedBy) {
        super(detail);
        this.type = type;
        this.blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    public URI getType() {
        return type;
    }

    /** What is standing in the way, one entry per blocker. Never null; may be empty. */
    public List<Map<String, String>> getBlockedBy() {
        return blockedBy;
    }
}
