// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.errors;

/**
 * A node body the lower layers refuse — a bad type label, an illegal label update, a field that
 * cannot be set on this node type.
 *
 * <p>Internal to the service layers: nothing renders it directly. The api converts it to a
 * {@code BadRequestException} at its boundary, which is where the wire shape is decided. It used to
 * carry a {@code ResponseError<FieldError>} — a rendered response body, assembled in
 * {@code datahub-infra}, two modules away from anything that answers an HTTP request.
 */
public class InvalidResourceException extends RuntimeException {

    private final String field;

    public InvalidResourceException(String message) {
        this(null, message);
    }

    /**
     * @param field the offending property, or null when the problem is not attributable to one
     */
    public InvalidResourceException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The offending property, or null. */
    public String getField() {
        return field;
    }
}
