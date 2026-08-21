// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Schema(name="DuplicateError", description="ExternalId already exists")
public class DuplicateError {

    private int code = 409;

    private String message;

    private Collection<Map<String, String>> duplicated = new ArrayList<>();

    public static ResponseError<DuplicateError> createError(String message, String externalId){
        ResponseError<DuplicateError> error = new ResponseError<>();
        var de = new DuplicateError();
        de.setMessage(message);
        de.getDuplicated().add(Map.of("externalId", externalId));
        error.setError(de);
        return error;
    }

    public int getCode() {
        return code;
    }

    public DuplicateError setCode(int code) {
        this.code = code;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public DuplicateError setMessage(String message) {
        this.message = message;
        return this;
    }

    public Collection<Map<String, String>> getDuplicated() {
        return duplicated;
    }

    public DuplicateError setDuplicated(Collection<Map<String, String>> duplicated) {
        this.duplicated = duplicated;
        return this;
    }
}
