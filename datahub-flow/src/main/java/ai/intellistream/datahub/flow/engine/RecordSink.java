// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

/**
 * Where a run's records go, in the order they happened. Called once per committed invocation for
 * each of its records; an invocation that is rolled back records nothing.
 */
@FunctionalInterface
public interface RecordSink {

    void accept(FlowRecord record);
}
