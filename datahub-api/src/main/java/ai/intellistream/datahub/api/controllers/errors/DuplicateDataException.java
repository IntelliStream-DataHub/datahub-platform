// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;

public class DuplicateDataException extends RuntimeException {

    private ResponseError<DuplicateError> error;

    public ResponseError<DuplicateError> getError() {
        return error;
    }

    public DuplicateDataException(ResponseError<DuplicateError> error){
        this.error = error;
    }

    public DuplicateDataException setError(ResponseError<DuplicateError> error) {
        this.error = error;
        return this;
    }
}
