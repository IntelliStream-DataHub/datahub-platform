// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.errors;



public class ResponseError<T> {

    private T error;

    public T getError() {
        return error;
    }

    public ResponseError<T> setError(T error) {
        this.error = error;
        return this;
    }
}
