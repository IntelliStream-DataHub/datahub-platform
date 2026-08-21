// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.transformers.EventTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The single point every write path goes through to have its external ids judged.
 *
 * <p><strong>Called from services, not controllers.</strong> The MCP tools call the services
 * directly and would otherwise bypass the check entirely; so would any future entry point. A
 * governance rule enforced at one of several doors is not enforced.
 *
 * <p>The contract is all-or-nothing and it is <em>structural</em>, not a consequence of rollback:
 * evaluation runs over the whole batch before anything is persisted, so a rejection means nothing
 * was attempted rather than something was attempted and undone. That matters because these writes
 * are not idempotent — a partial success leaves a caller unable to retry safely, having to work out
 * which of 500 items landed.
 *
 * <p>Two call sites per write, and the split is forced by the data model:
 * <ol>
 *   <li>{@link #check} before persisting — rejects, or returns the warnings;</li>
 *   <li>{@link #recordWarnings} after the flush — a finding names the entity it is about by node id,
 *       which does not exist until the row does. A rejected write therefore never reaches step two,
 *       which is exactly right: {@code NOT_OK} leaves no entity to attach a finding to.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyEnforcement {

    private final NamingPolicyEvaluator namingPolicyEvaluator;
    private final NamingPolicyResolver namingPolicyResolver;
    private final EventService eventService;

    /**
     * Judge a batch. Throws if anything is rejected; otherwise returns the warnings to report.
     *
     * @param candidates the batch, already reduced to what a policy needs to see
     * @return findings whose decision is {@code WARNING}. Empty when the batch is clean
     * @throws NamingPolicyViolationException if any item is {@code NOT_OK}. Nothing has been written
     */
    @Transactional(readOnly = true)
    public List<PolicyFinding> check(List<PolicyCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<PolicyFinding> findings = namingPolicyEvaluator.evaluate(candidates, newContext());

        List<PolicyFinding> rejections = findings.stream().filter(PolicyFinding::isRejection).toList();
        if (!rejections.isEmpty()) {
            throw new NamingPolicyViolationException(rejections, candidates.size());
        }

        List<PolicyFinding> warnings = findings.stream().filter(PolicyFinding::isWarning).toList();
        PolicyWarningContext.add(warnings.stream().map(PolicyWarning::from).toList());
        return warnings;
    }

    /**
     * Record the warnings raised by {@link #check} as events, now that the entities they are about
     * exist.
     *
     * <p>Findings do not go into the entity's {@code metadata} map. That was the cheap option and it
     * was rejected: {@code metadata} is a user-editable {@code Map<String,String>}, so a finding
     * written there could be deleted or forged by the same caller who triggered it. That is a note,
     * not a record. They go into the event store instead — a finding <em>is</em> an event, and the
     * store already provides the durability, filtering, ACLs and retention a bespoke table had to
     * reimplement. {@link PolicyFindingEvent} is the encoding.
     *
     * <p><b>Failure here must not fail the write.</b> A finding is a note about a write the platform
     * has already accepted; the entity is valid, the policy said so. Letting a Pulsar hiccup or a
     * malformed metadata value roll back the caller's resources would turn an advisory warning into
     * an outage, which is precisely the behaviour {@code warn} mode exists to avoid. So this logs
     * and moves on. The lost finding is recoverable — re-evaluating the entity raises it again.
     *
     * @param findings warnings from {@link #check}
     * @param writtenByExternalId maps each written entity's external id to what the finding needs to
     *                 point at it. Keyed on the external id because {@link PolicyCandidate#nodeId()}
     *                 is null on a create, which is the case that raises most findings
     */
    @Transactional
    public void recordWarnings(List<PolicyFinding> findings, Map<String, WrittenEntity> writtenByExternalId) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        String raisedBy = currentSubject();

        List<EventModel> events = new ArrayList<>(findings.size());
        for (PolicyFinding finding : findings) {
            WrittenEntity entity = writtenByExternalId.get(finding.externalId());
            if (entity == null || entity.nodeId() == null) {
                // The entity was not written after all (filtered out upstream, or the caller passed
                // an incomplete map). A finding has to name the thing it is about, and one that
                // names nothing is worse than none at all — it sits in the queue unactionable.
                log.debug("No node id for external id {}; finding not recorded", finding.externalId());
                continue;
            }
            events.add(EventTransformer.toPolicyFindingEvent(
                    finding, entity.nodeId(), entity.dataSetId(), raisedBy));
        }

        if (events.isEmpty()) {
            return;
        }
        try {
            eventService.createPlatformEvents(events);
        } catch (Exception e) {
            log.error("Failed to record {} policy finding(s); the write itself is unaffected: {}",
                    events.size(), e.getMessage(), e);
        }
    }

    /**
     * What a finding needs to know about an entity that was just written: which node to point at,
     * and which data set it landed in.
     *
     * <p>Exists so the two write paths ({@code ResourceService}, {@code TimeseriesService}) hand
     * over both facts together — the data set is not incidental, and why it has to travel with the
     * node id is on
     * {@link ai.intellistream.datahub.transformers.EventTransformer#toPolicyFindingEvent}.
     */
    public record WrittenEntity(Long nodeId, Long dataSetId) {
    }

    /**
     * A context for one batch, with policy resolution memoised inside it.
     *
     * <p>The tenant's policy set is resolved once here; {@link PolicyContext} then hands out the
     * per-data-set answer without another lookup. A thousand-item batch does one resolution.
     */
    public PolicyContext newContext() {
        NamingPolicyResolver.ResolvedPolicies policies = namingPolicyResolver.resolveForTenant();
        Function<Long, ai.intellistream.datahub.models.policy.NamingPolicy> resolver = policies::forDataSet;
        return new PolicyContext(TenantContext.getTenantId(), currentSubject(), resolver);
    }

    /** Exposed so preflight can evaluate without any of the persistence around it. */
    public List<PolicyFinding> evaluateOnly(List<PolicyCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return namingPolicyEvaluator.evaluate(candidates, newContext());
    }

    /**
     * The JWT {@code sub} of whoever is writing, recorded on a finding so a steward can go back to
     * the integration that produced the value rather than guessing. Null when there is no
     * authenticated principal, which is the case in tests and internal callers.
     */
    private static String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return authentication.getName();
    }
}
