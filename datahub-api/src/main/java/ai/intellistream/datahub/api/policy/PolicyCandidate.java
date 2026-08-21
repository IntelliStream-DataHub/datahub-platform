// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

/**
 * One item of a write batch, reduced to what a write policy needs to judge it.
 *
 * @param index              position in the submitted batch, so an error can name which item failed
 * @param externalId         the candidate value, verbatim as submitted
 * @param name               the entity's display name. Carried so a rejection can suggest a
 *                           <em>meaningful</em> alternative: the name is what a human chose, and
 *                           deriving from it gives {@code valve_pressure_sensors} where mangling a
 *                           broken id gives {@code vps}. See {@code ExternalIdSuggester}
 * @param dataSetId          the data set the item lands in, or null. Drives per-data-set policy
 *                           resolution
 * @param nodeId             the entity's id once it exists, or null on create. Findings are keyed on
 *                           it, so a warning raised during a create is attached after the flush
 * @param previousExternalId the stored value on an update, or null on create.
 *                           <p>This is what makes "validate only when the id changes" possible, and
 *                           that rule is not a nicety: without it, tightening a policy makes every
 *                           pre-existing resource unupdatable, and a steward fixing a typo in a
 *                           description gets a naming error on a field they did not touch
 */
public record PolicyCandidate(
        int index,
        String externalId,
        String name,
        Long dataSetId,
        Long nodeId,
        String previousExternalId) {

    public static PolicyCandidate forCreate(int index, String externalId, String name, Long dataSetId) {
        return new PolicyCandidate(index, externalId, name, dataSetId, null, null);
    }

    public static PolicyCandidate forUpdate(int index, String externalId, String name, Long dataSetId,
                                            Long nodeId, String previousExternalId) {
        return new PolicyCandidate(index, externalId, name, dataSetId, nodeId, previousExternalId);
    }

    /**
     * Whether this item's external id needs judging at all.
     *
     * <p>False when an update leaves the id byte-identical. Note "byte-identical", not
     * case-insensitively equal: changing {@code val-01} to {@code VAL-01} is a real change to a
     * stored value, so it gets evaluated even though it does not change what the id collides with.
     */
    public boolean requiresEvaluation() {
        return externalId != null && !externalId.equals(previousExternalId);
    }
}
