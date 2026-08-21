// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.PolicyCandidate;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.policy.NamingCheckForm;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The preflight policy check: what would a policy do, asked without doing it.
 *
 * <p>This is all that is left of a service that also served the review queue. Findings are events
 * now, so reading them is {@code POST /events/filter} like any other event and needs nothing here —
 * see {@link ai.intellistream.datahub.models.policy.PolicyFindingEvent} for the encoding. Preflight
 * is different in kind: it writes nothing and stores nothing, so there is no store to read it back
 * from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyCheckService {

    private final PolicyEnforcement policyEnforcement;
    private final DataSecurity dataSecurity;

    /**
     * Run the naming policy over candidate external ids and report what it would do. Writes nothing.
     *
     * <p>Uses the same evaluator as the write path rather than a second implementation — a preflight
     * that can disagree with the real check is worse than no preflight, because it teaches people to
     * trust an answer that is sometimes wrong.
     *
     * <p>Returns findings only for ids that are not {@code OK}; a clean batch returns an empty list.
     */
    @Transactional(readOnly = true)
    public List<PolicyFinding> check(NamingCheckForm form) {
        // Preflight against a specific data set reveals that data set's policy, so require the same
        // read access the data set itself needs.
        if (form.getDataSetId() != null) {
            dataSecurity.assertCanReadDataSet(form.getDataSetId());
        }

        // Names pair with ids by position, so a partial list would silently attach the wrong name to
        // the wrong id and produce a confidently-wrong suggestion. Refuse rather than guess.
        if (form.hasNames() && form.getNames().size() != form.getExternalIds().size()) {
            var error = new BadRequestError();
            error.setMessage("If names are supplied there must be exactly one per external id: got "
                    + form.getNames().size() + " names for " + form.getExternalIds().size() + " external ids.");
            error.addFieldError("names", String.valueOf(form.getNames().size()));
            throw new BadRequestException(new ResponseError<BadRequestError>().setError(error));
        }

        List<PolicyCandidate> candidates = new ArrayList<>(form.getExternalIds().size());
        int index = 0;
        for (String externalId : form.getExternalIds()) {
            candidates.add(PolicyCandidate.forCreate(index, externalId, form.nameFor(index), form.getDataSetId()));
            index++;
        }
        return policyEnforcement.evaluateOnly(candidates);
    }
}
