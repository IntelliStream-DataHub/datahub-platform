// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;


@Getter
@Schema(name="BadRequestError", description="Bad request, validation error with submitted fields.")
public class BadRequestError {

    private int code = 400;

    private String message;

    private Collection<Map<String, String>> fields = new ArrayList<>();

    public static ResponseError<BadRequestError> createError(String message, String externalId){
        ResponseError<BadRequestError> error = new ResponseError<>();
        var de = new BadRequestError();
        de.setMessage(message);
        de.getFields().add(Map.of("externalId", externalId));
        error.setError(de);
        return error;
    }

    public BadRequestError setCode(int code) {
        this.code = code;
        return this;
    }

    public BadRequestError setMessage(String message) {
        this.message = message;
        return this;
    }

    public BadRequestError setFields(Collection<Map<String, String>> fields) {
        this.fields = fields;
        return this;
    }

    public BadRequestError addFieldError(String field, String message){
        this.fields.add(Map.of(field, message));
        return this;
    }
}
