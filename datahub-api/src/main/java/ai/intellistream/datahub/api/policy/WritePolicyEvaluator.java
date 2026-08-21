// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.models.policy.PolicyFinding;

import java.util.List;

/**
 * Judges a whole write batch before anything is persisted.
 *
 * <p>The batch signature is the design, not a convenience. Validating up front rather than per item
 * makes all-or-nothing <em>structural</em> instead of dependent on a rollback, and it lets one error
 * response name every offending item rather than only the first one to hit the database. It also
 * lets a rule that needs to see the whole batch — the near-duplicate guard has to compare items
 * against each other, or submitting two colliding ids together trivially bypasses it — be expressed
 * without a second pass.
 *
 * <p>Implementations are called from services rather than controllers, so the MCP tools and any
 * future entry point are covered by the same check.
 *
 * @param <T> the item type; {@link PolicyCandidate} for everything that judges external ids
 */
public interface WritePolicyEvaluator<T> {

    /**
     * @return one finding per non-conforming item. An empty list means the batch is clean; items
     *         that pass produce no finding at all.
     */
    List<PolicyFinding> evaluate(List<T> batch, PolicyContext context);
}
