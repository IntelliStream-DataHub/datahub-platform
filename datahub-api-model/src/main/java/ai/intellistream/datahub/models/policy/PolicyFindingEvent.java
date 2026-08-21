// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

/**
 * How a policy finding is encoded as an event.
 *
 * <p>A finding is a statement that something happened at a point in time — a policy looked at an
 * external id, allowed it through, and thought someone should know. That is an event, so it lives in
 * the event store rather than in a table of its own. What used to be columns are now either event
 * fields (where the event model already has one that means the same thing) or {@code metadata}
 * entries (where it does not).
 *
 * <p>The constants here are the whole mapping, and they are in {@code api-model} rather than hidden
 * in the api because they are a wire contract: a finding is now an ordinary event, so anything that
 * can read events — the SDKs, the MCP tools, a customer's own query — can read findings without the
 * dedicated endpoint. That is the main thing the move buys, and it only holds if the encoding is
 * written down where those readers can see it.
 *
 * <table>
 *   <caption>Field mapping</caption>
 *   <tr><th>Finding</th><th>Event</th></tr>
 *   <tr><td>which finding this is about</td><td>{@code externalId} — see {@link #externalIdFor}</td></tr>
 *   <tr><td>which policy fired</td><td>{@code subType}, and {@code source} as {@link #sourceFor}</td></tr>
 *   <tr><td>what is wrong</td><td>{@code description}</td></tr>
 *   <tr><td>the entity it is about</td><td>{@code relatedResources}</td></tr>
 *   <tr><td>the entity's data set</td><td>{@code dataSetId}</td></tr>
 *   <tr><td>when this happened</td><td>{@code eventTime}</td></tr>
 *   <tr><td>what it asserts</td><td>{@code status} — {@code OPEN} or {@code RESOLVED}</td></tr>
 *   <tr><td>offending value, suggestion, who wrote it</td><td>{@code metadata}</td></tr>
 * </table>
 *
 * <h2>A finding is a stream, not a row</h2>
 *
 * <p>Nothing here is ever updated in place. Raising a finding appends an {@code OPEN} event;
 * resolving it appends a {@code RESOLVED} event carrying the <em>same</em> {@link #externalIdFor}
 * external id. The current state of a finding is not stored anywhere — it is
 * <strong>derived</strong>: take every event sharing that external id, order by {@code eventTime}
 * ascending, and the last one wins.
 *
 * <p>That is how the event store already models a lifecycle — several events sharing one external id
 * are one logical thing moving through its states, the way an order goes created → approved → paid —
 * so a finding uses the mechanism rather than reaching around it for an in-place mutation.
 *
 * <p>It also makes the awkward cases fall out for free instead of needing rules:
 * <ul>
 *   <li><b>Reopening.</b> A steward resolves a finding; later the external id changes to another
 *       non-conforming value and the policy raises again. That appends a fresh {@code OPEN} after the
 *       {@code RESOLVED}, so the replay says open. No reopen logic, no "did the offending value
 *       change" comparison — the timeline just is what it is.</li>
 *   <li><b>Idempotence.</b> Two identical raises collapse, because the {@code OPEN} event's id is
 *       derived from what it asserts (see {@link #raiseIdSeed}). Two resolves do not collapse, and do
 *       not need to: replaying {@code OPEN, RESOLVED, RESOLVED} gives resolved either way.</li>
 *   <li><b>History.</b> "This was raised in March, waved through in April, and came back in June" is
 *       a question the queue can now answer, which a single mutated row could not.</li>
 * </ul>
 *
 * <p>The cost is that open-ness cannot be filtered server-side. {@code status = "OPEN"} would match
 * the raise of a finding that has since been resolved, so a reader wanting the outstanding ones must
 * fetch the stream and fold it. That is the honest trade: the store holds facts, and "is it still
 * open" is a conclusion drawn from them rather than a fact of its own.
 *
 * <p><b>Why {@code subType} and {@code status} rather than metadata for those two.</b> {@code subType}
 * is what the queue filters on, and in the event store {@code sub_type} is an indexed column while
 * {@code metadata} is a map. {@code status} sits beside it because it is the same kind of thing — a
 * small, closed set of values describing the event — even though, per above, it is folded rather than
 * filtered.
 *
 * <p>The policy therefore appears in two places, which is deliberate and not duplication for its own
 * sake: {@code subType} is the dimension you filter and group by, {@code source} says which producer
 * emitted the event. Those are the same two roles they play for an ordinary ingested event, where
 * {@code subType} might be {@code Electrical} and {@code source} {@code SAP}.
 */
public final class PolicyFindingEvent {

    private PolicyFindingEvent() {
    }

    /**
     * The {@code type} every finding event carries, and therefore the one filter that separates
     * findings from the tenant's real events.
     *
     * <p>Findings share the events table with everything else a tenant ingests, so this value has to
     * be specific enough not to collide with a type someone else might legitimately use. It is
     * matched exactly, never by prefix.
     */
    public static final String TYPE = "policy_finding";

    /** Prefix of {@code source} on every finding event; see {@link #sourceFor}. */
    public static final String SOURCE_PREFIX = "datahub_policy_";

    /**
     * The longest {@code source} the event model accepts. Mirrors the {@code @Size} on
     * {@code EventModel.source} — exceeding it fails validation, and a finding that fails validation
     * takes the caller's whole write down with it.
     */
    private static final int SOURCE_MAX_LENGTH = 128;

    /** {@code status} of the event that raises a finding: this entity violates this policy. */
    public static final String STATUS_OPEN = "OPEN";

    /**
     * {@code status} of the event that closes a finding: a steward has judged the violation
     * acceptable.
     *
     * <p>Resolved is a judgement, not a fix — the entity still violates the policy. And because this
     * is an appended event rather than an edit, it does not erase the raise it answers: the finding's
     * history stays legible, and a later raise for a new offending value simply lands after it.
     *
     * <p>When it was resolved is this event's own {@code eventTime}. <em>Who</em> resolved it is not
     * recorded: resolving is an ordinary event write, with no policy-aware step to stamp a verified
     * subject, and a client-supplied name would be an unverifiable claim in a field that reads like
     * an audit record.
     */
    public static final String STATUS_RESOLVED = "RESOLVED";

    /**
     * The value that tripped the policy.
     *
     * <p>Kept verbatim so re-evaluation can tell "same problem as before" from "the id changed and
     * this is a new problem" — the second case is the only one that reopens a resolved finding.
     */
    public static final String META_OFFENDING_VALUE = "offendingValue";

    /** A conforming alternative where one could be derived. Absent when none could. */
    public static final String META_SUGGESTION = "suggestion";

    /**
     * Subject of whoever wrote the offending value, so a steward can go back to the integration
     * that produced it rather than guessing. Absent when there was no authenticated principal.
     */
    public static final String META_RAISED_BY = "raisedBy";

    /**
     * The external id shared by every event in one finding's lifecycle: the finding about
     * {@code nodeId} under {@code policyExternalId}.
     *
     * <p>This is the correlation key, and it is what replaces the old
     * {@code UNIQUE (node_id, policy_name)} constraint. Where that constraint forced one row per
     * entity-and-policy, this gives one <em>stream</em> per entity-and-policy: the raise and every
     * later resolve or re-raise carry this same value, and folding them in {@code eventTime} order
     * is what produces the finding's current state. Deriving it from the pair rather than minting one
     * means a resolve can name the finding it answers without looking anything up.
     *
     * <p>Keyed on the node id and not the entity's external id, deliberately. The external id is the
     * <em>offending value</em> here — it is the thing the policy is complaining about and the thing
     * a steward is most likely to change. Keying on it would mean renaming a resource silently
     * abandoned its finding and started a second stream, leaving the queue holding a complaint about
     * a value that no longer exists anywhere, and stranding the resolve that was meant to close it.
     */
    public static String externalIdFor(String policyExternalId, long nodeId) {
        return TYPE + "_" + policyExternalId + "_" + nodeId;
    }

    /**
     * The string a raise event's id is derived from, so that raising the same finding twice writes
     * one event rather than two.
     *
     * <p>The offending value is part of it, and that is the whole point. Re-evaluating an entity
     * whose external id has not changed asserts exactly what the last raise asserted, so it should
     * collapse onto it — but a raise for a <em>different</em> non-conforming value is a new fact
     * about the entity and must be appended, including when it follows a resolve. Keying on the
     * value is what tells those two apart without reading the stream first.
     *
     * <p>Deliberately not applied to resolve events. Two resolves are harmless — replaying
     * {@code OPEN, RESOLVED, RESOLVED} yields resolved either way — so a resolve takes an ordinary
     * generated id, and does not need a seed that would have to include a timestamp to stay unique.
     *
     * @return a seed to hash into an event id; see {@code IdGenerator.deterministicUUID}
     */
    public static String raiseIdSeed(String policyExternalId, long nodeId, String offendingValue) {
        // The separator cannot occur in an external id (the charset floor admits no whitespace), so
        // no two distinct triples can produce the same seed by running their parts together.
        return externalIdFor(policyExternalId, nodeId) + "\n" + offendingValue;
    }

    /**
     * The {@code source} for findings raised by {@code policyExternalId}, e.g.
     * {@code datahub_policy_naming_default}.
     *
     * <p>Naming the specific policy rather than the platform in general is what makes {@code source}
     * useful on this event: a tenant runs several policies, and "which one produced this" is the
     * question a reader of a raw event has. It also keeps the existing source-listing endpoint
     * meaningful — the tenant's sources become {@code SAP}, {@code datahub_policy_naming_default}
     * and so on, rather than one undifferentiated {@code datahub_policy}.
     *
     * <p>Truncated to what the event model accepts. A policy external id long enough to overflow
     * would otherwise fail validation, and a finding must never be able to fail the write that
     * triggered it — losing the tail of a source is a far smaller problem than rejecting a
     * legitimate resource because the complaint about it did not fit.
     */
    public static String sourceFor(String policyExternalId) {
        String source = SOURCE_PREFIX + policyExternalId;
        return source.length() <= SOURCE_MAX_LENGTH ? source : source.substring(0, SOURCE_MAX_LENGTH);
    }
}
