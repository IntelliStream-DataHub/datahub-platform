// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

/** A conversion that did not produce a usable model. */
public class RvmConversionException extends Exception {

    public RvmConversionException(String message) {
        super(message);
    }

    public RvmConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
