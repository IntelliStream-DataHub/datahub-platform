// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

/** Messages that end up on the run's page, tagged with the processor that wrote them. */
public interface RunLog {

    void info(String message);

    void warn(String message);
}
