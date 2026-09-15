// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * An identifier the tenant already uses.
 *
 * <p>Carries what collided rather than a rendered body: throw sites used to build a
 * {@code DuplicateError} inside a {@code ResponseError} by hand — five lines to say two things, and
 * a wrapper the advice immediately unwrapped. {@code DuplicateError.code} went with it; it was
 * always 409, and fourteen controllers read the status back out of the payload rather than off the
 * response.
 */
public class DuplicateDataException extends RuntimeException {

    // transient: rebuilt per request at the throw site, never Java-serialized with the throwable.
    private final transient List<Map<String, String>> duplicated;

    public DuplicateDataException(String detail) {
        this(detail, List.of());
    }

    /**
     * @param duplicated the identifiers that collided, as {@code field -> value} — e.g.
     *                   {@code {"externalId": "sensor_temp_room_a"}}
     */
    public DuplicateDataException(String detail, Collection<Map<String, String>> duplicated) {
        super(detail);
        this.duplicated = duplicated == null ? List.of() : List.copyOf(duplicated);
    }

    /** The single-identifier case, which is most of them. */
    public static DuplicateDataException of(String detail, String field, String value) {
        return new DuplicateDataException(detail, List.of(Map.of(field, value)));
    }

    /** Never null; empty when the collision could not be attributed to a named identifier. */
    public List<Map<String, String>> getDuplicated() {
        return duplicated;
    }
}
