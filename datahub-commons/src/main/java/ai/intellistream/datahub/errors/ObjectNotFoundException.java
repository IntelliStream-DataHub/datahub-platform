// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.errors;

public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(String errorMessage) {
        super(errorMessage);
    }

}