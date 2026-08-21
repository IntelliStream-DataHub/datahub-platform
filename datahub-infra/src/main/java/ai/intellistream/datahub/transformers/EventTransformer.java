// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyFindingEvent;
import ai.intellistream.datahub.tenant.TenantContext;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link EventModel}s the platform raises about itself, rather than ones a caller submitted.
 *
 * <p>Currently one kind: the policy finding. The encoding it produces is
 * {@link PolicyFindingEvent}, which is where the field mapping and the reasoning behind it live.
 */
public class EventTransformer {

    /**
     * The event that raises one policy finding.
     *
     * <p>This only ever appends. The finding's current state is folded from every event sharing its
     * external id, so nothing here reads or amends what came before — see {@link PolicyFindingEvent}
     * for the lifecycle.
     *
     * <p>The id is derived from what the event asserts — the entity, the policy and the offending
     * value — rather than minted fresh, so that re-evaluating an unchanged entity collapses onto the
     * raise already stored instead of appending an identical one. A raise for a <em>different</em>
     * non-conforming value asserts something new, gets a different id, and is appended as it should
     * be. The store's own deduplication does the work; this never has to look first.
     *
     * <p>{@code eventTime} is when this happened, and it is both what the queue orders and pages on
     * and what the fold resolves ties by. It is always now: an appended fact is dated when it
     * occurred, and unlike the row-shaped design this replaces there is no question of preserving an
     * earlier timestamp, because nothing is being overwritten.
     *
     * @param finding   the {@code WARNING} verdict to record
     * @param nodeId    the entity the finding is about, which must already exist — a finding names
     *                  its subject by id, and one naming nothing is unactionable
     * @param dataSetId the entity's data set, or null when it belongs to none. Carried because the
     *                  finding event needs one of its own: it is what the review queue filters on
     *                  and what the dataset ACL applies to on read, so copying it here is what keeps
     *                  a finding invisible to anyone who cannot see the entity it is about, without
     *                  a rule someone has to remember to enforce
     * @param raisedBy  subject of whoever wrote the offending value, or null when there was no
     *                  authenticated principal
     */
    public static EventModel toPolicyFindingEvent(PolicyFinding finding, long nodeId, Long dataSetId,
                                                  String raisedBy) {
        String policy = finding.policyExternalId();

        EventModel event = new EventModel();
        String externalId = PolicyFindingEvent.externalIdFor(policy, nodeId);
        event.setExternalId(externalId);
        event.setId(IdGenerator.deterministicUUID(
                PolicyFindingEvent.raiseIdSeed(policy, nodeId, finding.externalId()),
                TenantContext.getTenantId()).toString());
        event.setType(PolicyFindingEvent.TYPE);
        event.setSubType(policy);
        event.setSource(PolicyFindingEvent.sourceFor(policy));
        event.setStatus(PolicyFindingEvent.STATUS_OPEN);
        event.setDescription(finding.message());
        event.setDataSetId(dataSetId);
        event.setEventTime(ZonedDateTime.now());
        // Id only, no external id. The entity's external id is the offending value, so it is
        // deliberately not used to identify the entity here — the node id carries that. Recording
        // the name as well would leave the finding pointing at one that no longer resolves the
        // moment a steward renames the thing to fix the complaint. This survives because
        // createPlatformEvents skips the resolution the public create path runs, which would
        // otherwise fill the missing side in.
        event.setRelatedResources(List.of(IdCollection.createFromId(nodeId)));

        Map<String, String> metadata = new HashMap<>();
        metadata.put(PolicyFindingEvent.META_OFFENDING_VALUE, finding.externalId());
        if (finding.suggestion() != null) {
            metadata.put(PolicyFindingEvent.META_SUGGESTION, finding.suggestion());
        }
        if (raisedBy != null) {
            metadata.put(PolicyFindingEvent.META_RAISED_BY, raisedBy);
        }
        event.setMetadata(metadata);
        return event;
    }
}
