// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

public interface Datapoint {
    Object getValue();

    long getTimestamp();

    void setValue(Object value);

    void setTimestamp(long timestamp);
}
