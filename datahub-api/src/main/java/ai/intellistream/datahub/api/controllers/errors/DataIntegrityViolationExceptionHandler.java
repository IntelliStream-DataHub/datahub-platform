// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * A database constraint the caller can do something about, as a 409 naming the field.
 *
 * <p>Postgres says {@code duplicate key value violates unique constraint "node_external_id_hash_key"}.
 * That names an index, not a field, and says nothing a caller can act on — so the constraint name is
 * translated into the field they sent. The mapping came from {@code BuildErrorResponse}, which seven
 * controllers each called from their own {@code catch}, and which returned a {@code DataWrapper}:
 * a success-shaped envelope used as an error body.
 *
 * <p>An unrecognised constraint is deliberately still a 409 with no {@code fields}: it is a
 * conflict whether or not this class has a name for it, and inventing a field would be worse than
 * omitting one. The full exception is logged for whoever adds the mapping.
 */
@RestControllerAdvice
@Slf4j
public class DataIntegrityViolationExceptionHandler {

    /** Constraint name to the request field it is really about. */
    private static final Map<String, Map<String, String>> BY_CONSTRAINT = Map.of(
            "node_external_id_hash_key", Map.of("externalId", "External id already exists."),
            "label_hash_key", Map.of("name", "Label with same name already exists."),
            "relationship_hash_key", Map.of("name", "Relationship type with same name already exists."),
            "edge_unique_key", Map.of("relationship",
                    "This relationship already exists between these two resources."),
            "data_set_parent_id_fk", Map.of("parent",
                    "Cannot delete a data set with children that references the data set you want to delete."));

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handle(DataIntegrityViolationException ex) {
        String constraint = ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException hibernate
                ? hibernate.getConstraintName()
                : null;
        Map<String, String> field = constraint == null ? null : BY_CONSTRAINT.get(constraint);

        if (field == null) {
            log.warn("Unmapped constraint violation ({}): {}", constraint, ex.getMessage());
            return Problems.duplicate("The request conflicts with data that already exists.", List.of());
        }
        log.debug("Rejecting write, constraint {}", constraint);
        return Problems.duplicate(field.values().iterator().next(), List.of(field));
    }
}
