// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.validation;

/**
 * A dependency-free validation-error holder for the partial-update {@code *Fields}
 * models (RelFields, DataSetFields, ResourceFields, TimeseriesFields, EventFields).
 *
 * <p>Replaces {@code org.springframework.validation.ObjectError} so the contract
 * types stay framework-free. The {@code codes} / {@code arguments} were never read
 * by consumers (they only use {@link #getObjectName()} and
 * {@link #getDefaultMessage()}); the four-argument constructor is kept purely so the
 * existing call sites compile unchanged.
 */
public class FieldValidationError {

    private final String objectName;
    private final String[] codes;
    private final Object[] arguments;
    private final String defaultMessage;

    public FieldValidationError(String objectName, String defaultMessage) {
        this(objectName, null, null, defaultMessage);
    }

    public FieldValidationError(String objectName, String[] codes, Object[] arguments, String defaultMessage) {
        this.objectName = objectName;
        this.codes = codes;
        this.arguments = arguments;
        this.defaultMessage = defaultMessage;
    }

    public String getObjectName() {
        return objectName;
    }

    public String[] getCodes() {
        return codes;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
