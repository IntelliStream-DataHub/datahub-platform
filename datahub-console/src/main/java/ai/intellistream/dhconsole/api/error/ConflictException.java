// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.api.error;

import lombok.Data;

@Data
public class ConflictException extends RuntimeException {

    private String errorMessage;
    private Integer errorCode;

    // Constructor, getters, and setters

    public ConflictException(String errorMessage, Integer errorCode) {
        super(errorMessage);
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
    }

}
