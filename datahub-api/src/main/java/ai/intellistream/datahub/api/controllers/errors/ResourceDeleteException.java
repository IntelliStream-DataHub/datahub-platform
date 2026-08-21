// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;

public class ResourceDeleteException extends RuntimeException {

    // transient: the response body is rebuilt per request and must never be Java-serialized with
    // the throwable.
    private final transient ResponseError<BadRequestError> error;

    public ResourceDeleteException(String errorMessage) {
        super(errorMessage);
        var be = new BadRequestError();
        be.setMessage(errorMessage);
        var resp = new ResponseError<BadRequestError>();
        resp.setError(be);
        this.error = resp;
    }

    public ResourceDeleteException(ResponseError<BadRequestError> error) {
        super(error.getError().getMessage());
        this.error = error;
    }

    public ResponseError<BadRequestError> getError() {
        return error;
    }

}
