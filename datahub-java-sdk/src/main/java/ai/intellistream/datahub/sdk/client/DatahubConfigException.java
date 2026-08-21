// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.client;

/** Thrown when the SDK is misconfigured (missing base URL or credentials). */
public class DatahubConfigException extends RuntimeException {

    public DatahubConfigException(String message) {
        super(message);
    }

    public DatahubConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
