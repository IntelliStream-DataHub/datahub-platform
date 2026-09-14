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

    /**
     * The shape a throw site actually has: what collided, and the identifiers that collided.
     *
     * <p>Callers used to build a {@code DuplicateError} inside a {@code ResponseError} by hand at
     * each throw site — five lines to say two things, and a wrapper the advice immediately unwraps.
     */
    public DuplicateDataException(String message, java.util.Collection<java.util.Map<String, String>> duplicated) {
        var payload = new DuplicateError();
        payload.setMessage(message);
        payload.setDuplicated(new java.util.ArrayList<>(duplicated));
        this.error = new ResponseError<>();
        this.error.setError(payload);
    }

    public DuplicateDataException setError(ResponseError<DuplicateError> error) {
        this.error = error;
        return this;
    }
}
