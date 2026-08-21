// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.responses;

import ai.intellistream.datahub.api.responses.DataWrapper;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

public class BuildErrorResponse {

    public static DataWrapper<?> createConstraintViolationError(ConstraintViolationException cve){
        var violations = cve.getConstraintViolations();
        DataWrapper<Map<String, String>> errors = new DataWrapper<>();
        violations.forEach( action -> {
            String msg = action.getMessage();
            String path = action.getPropertyPath().toString();
            errors.getItems().add(Map.of(path, msg));
        });
        return errors;
    }

    public static DataWrapper<?> createDataIntegrityViolationError(DataIntegrityViolationException cve) {
        DataWrapper<Map<String, String>> errors = new DataWrapper<>();
        var cause = cve.getCause();
        if(cause instanceof org.hibernate.exception.ConstraintViolationException){
            String constraintName = ((org.hibernate.exception.ConstraintViolationException) cause).getConstraintName();
            if(constraintName != null && constraintName.equals("data_set_parent_id_fk")){
                errors.getItems().add(Map.of("parent", "Cannot delete a data set with children that references the data set you want to delete."));
            }
            else if(constraintName != null && constraintName.equals("label_hash_key")){
                errors.getItems().add(Map.of("name", "Label with same name already exists."));
            } else if(constraintName != null && constraintName.equals("node_external_id_hash_key")){
                errors.getItems().add(Map.of("externalId", "External id already exists."));
            } else if(constraintName != null && constraintName.equals("relationship_hash_key")){
                errors.getItems().add(Map.of("name", "Relationship type with same name already exists."));
            } else if(constraintName != null && constraintName.equals("edge_unique_key")){
                errors.getItems().add(Map.of("relationship", "This relationship already exists between these two resources."));
            }
        }
        return errors;
    }
}
