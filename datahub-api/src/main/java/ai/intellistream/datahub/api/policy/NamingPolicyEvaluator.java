// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.models.policy.ExternalIdSuggester;
import ai.intellistream.datahub.models.policy.NamingPolicy;
import ai.intellistream.datahub.models.policy.NamingPreset;
import ai.intellistream.datahub.models.policy.PolicyDecision;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyMode;
import ai.intellistream.datahub.repositories.policy.NearDuplicateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The naming policy: two independent rules feeding one decision per item.
 *
 * <p>Applies to <strong>resources and data sets only</strong>. Events are out of scope entirely and
 * see nothing but the charset floor — an event external id is the source system's key for the
 * subject the event is about, not a name someone chose, and the policy's rules are meaningless
 * there in any case: events deliberately share external ids, so uniqueness does not apply and a
 * near duplicate is the normal case rather than an anomaly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NamingPolicyEvaluator implements WritePolicyEvaluator<PolicyCandidate> {

    private final NearDuplicateRepository nearDuplicateRepository;

    @Override
    public List<PolicyFinding> evaluate(List<PolicyCandidate> batch, PolicyContext context) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }

        List<PolicyCandidate> toJudge = batch.stream().filter(PolicyCandidate::requiresEvaluation).toList();
        if (toJudge.isEmpty()) {
            return List.of();
        }

        // One query for the whole batch. Built before the per-item loop so the loop stays pure.
        Map<String, String> existingByFolded = lookupNearDuplicates(toJudge);

        List<PolicyFinding> findings = new ArrayList<>();
        // Tracks folded values already claimed by an earlier item in THIS batch. Without it the
        // guard is trivially bypassed: submit `pump-a-01` and `pump_a_01` together and neither
        // exists yet, so neither collides with stored data. It also feeds the suggester, so a
        // suggestion is never a value an earlier item in the same request has already taken.
        Map<String, String> claimedInBatch = new LinkedHashMap<>();
        Predicate<String> foldedIsTaken =
                candidate -> existingByFolded.containsKey(candidate) || claimedInBatch.containsKey(candidate);

        for (PolicyCandidate item : toJudge) {
            NamingPolicy policy = context.namingPolicyFor(item.dataSetId());
            String externalId = item.externalId();
            String folded = ExternalIds.fold(externalId);

            PolicyFinding presetFinding = checkPreset(item, policy, foldedIsTaken);

            String collidesWith = existingByFolded.get(folded);
            if (collidesWith == null) {
                collidesWith = claimedInBatch.get(folded);
            }
            PolicyFinding duplicateFinding = collidesWith == null
                    ? null
                    : nearDuplicateFinding(item, policy, collidesWith, foldedIsTaken);

            claimedInBatch.putIfAbsent(folded, externalId);

            // One decision per item. When both rules fire, the more severe wins — a caller told
            // "warning" about something that is actually going to be rejected has been misled.
            PolicyFinding worst = moreSevere(presetFinding, duplicateFinding);
            if (worst != null) {
                findings.add(worst);
            }
        }
        return findings;
    }

    /**
     * The near-duplicate lookup for a whole batch: fold every candidate, ask once.
     *
     * <p>Entities being updated are excluded, or renaming {@code pump_a_01}'s description would have
     * it report itself as its own near-duplicate.
     */
    private Map<String, String> lookupNearDuplicates(List<PolicyCandidate> batch) {
        Set<String> folded = new LinkedHashSet<>();
        Set<Long> updating = new HashSet<>();
        for (PolicyCandidate item : batch) {
            folded.add(ExternalIds.fold(item.externalId()));
            if (item.nodeId() != null) {
                updating.add(item.nodeId());
            }
        }
        return nearDuplicateRepository.findExistingByFoldedValue(folded, updating);
    }

    private PolicyFinding checkPreset(PolicyCandidate item, NamingPolicy policy,
                                      Predicate<String> foldedIsTaken) {
        if (policy.matchesPreset(item.externalId())) {
            return null;
        }
        return new PolicyFinding(
                item.index(),
                item.externalId(),
                policy.mode().toDecision(),
                policy.policyExternalId(),
                "Does not match naming policy '" + policy.describePreset() + "'.",
                ExternalIdSuggester.suggest(item.name(), item.externalId(), policy, foldedIsTaken));
    }

    /**
     * The advice for a near duplicate lives in the message, not the suggestion field, and that split
     * is deliberate.
     *
     * <p>The likeliest truth is that the two ids are the same asset, so the message names the
     * existing one and says to use it. That cannot be the {@code suggestion}, because the suggestion
     * field is what a client offers as a one-click fix and applying it here would trade a naming
     * rejection for a duplicate-external-id rejection.
     *
     * <p>So the suggestion is a genuinely <em>free</em> alternative, and often there is none: every
     * form derived from the same name or the same id tends to fold to the same taken value, and the
     * suggester correctly returns null rather than inventing a discriminator like {@code _2} — which
     * would be the platform guessing that two similar ids are two different assets, the very
     * judgement it is asking the caller to make.
     */
    private PolicyFinding nearDuplicateFinding(PolicyCandidate item, NamingPolicy policy,
                                               String collidesWith, Predicate<String> foldedIsTaken) {
        return new PolicyFinding(
                item.index(),
                item.externalId(),
                policy.nearDuplicateMode().toDecision(),
                policy.policyExternalId(),
                "'" + item.externalId() + "' is a near duplicate of the existing '" + collidesWith
                        + "': they differ only in separators or case. Two identifiers this similar are "
                        + "almost always meant to be one, and a search for either finds only its own. "
                        + "If they are the same thing, use '" + collidesWith + "'.",
                ExternalIdSuggester.suggest(item.name(), item.externalId(), policy, foldedIsTaken));
    }

    private static PolicyFinding moreSevere(PolicyFinding a, PolicyFinding b) {
        if (a == null) return b;
        if (b == null) return a;
        return rank(a.decision()) >= rank(b.decision()) ? a : b;
    }

    private static int rank(PolicyDecision decision) {
        return switch (decision) {
            case NOT_OK -> 2;
            case WARNING -> 1;
            case OK -> 0;
        };
    }

    /** Exposed for the preflight endpoint, which needs the same rules with no batch around them. */
    public PolicyMode nearDuplicateModeOf(NamingPolicy policy) {
        return policy.nearDuplicateMode();
    }
}
