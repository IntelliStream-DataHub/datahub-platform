// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.errors;

import lombok.Data;

@Data
public class FieldError {

    private String field;
    private String errorMessage;

}
