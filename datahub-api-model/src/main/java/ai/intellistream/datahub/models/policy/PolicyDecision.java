// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

/**
 * The verdict a policy returns for one item.
 *
 * <p>Three values rather than two, and the middle one is the whole point. A binary result forces
 * every convention to be either mandatory or invisible, which leaves a facility that wants to
 * tighten its naming gradually with nowhere to stand: switching a rule on rejects the next thousand
 * ingests, and leaving it off tells nobody anything. {@link #WARNING} is what makes a gradual
 * rollout expressible — and it is only meaningful because findings are persisted, so it means
 * "allowed, and in the steward's queue" rather than "allowed and forgotten".
 */
public enum PolicyDecision {

    /** Conforms. Nothing is recorded. */
    OK,

    /** Does not conform, but is allowed through. Persisted as a finding and returned in the response. */
    WARNING,

    /** Does not conform and is rejected. The whole batch fails before anything is written. */
    NOT_OK
}
