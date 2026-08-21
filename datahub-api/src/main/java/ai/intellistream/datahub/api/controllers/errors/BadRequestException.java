// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;

public class BadRequestException extends RuntimeException {

    private ResponseError<BadRequestError> error;

    public ResponseError<BadRequestError> getError() {
        return error;
    }

    public BadRequestException(ResponseError<BadRequestError> error){
        this.error = error;
    }

    public BadRequestException setError(ResponseError<BadRequestError> error) {
        this.error = error;
        return this;
    }

}
