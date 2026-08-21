// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.errors;

import lombok.Getter;

@Getter
public class InvalidResourceException extends RuntimeException {

    private ResponseError<FieldError> error;

    public InvalidResourceException(ResponseError<FieldError> error){
        this.error = error;
    }

    public InvalidResourceException setError(ResponseError<FieldError> error) {
        this.error = error;
        return this;
    }

}
