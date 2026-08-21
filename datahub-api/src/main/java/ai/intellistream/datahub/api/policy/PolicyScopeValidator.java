// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.PolicyType;
import ai.intellistream.datahub.models.policy.NamingPolicy;
import ai.intellistream.datahub.models.policy.NamingPreset;
import ai.intellistream.datahub.models.policy.PolicyScope;

import java.util.Map;

/**
 * Checks a policy against the scope its type declares, and validates naming-specific configuration,
 * at save time.
 *
 * <p>This is where {@link PolicyScope} earns its keep. Without an attachment check the enum is
 * documentation: nothing would stop a tenant-only rule being hung off a single data set, where it
 * would look configured and do nothing.
 *
 * <p>Validating the regex here rather than on the write path is the same idea applied to a different
 * mistake. A malformed pattern discovered during ingest is an error shown to an integration that
 * cannot fix it; discovered at save time it is an error shown to the person who typed it.
 */
public final class PolicyScopeValidator {

    private PolicyScopeValidator() {
    }

    /**
     * @throws BadRequestException if the policy is attached somewhere its type does not allow, or
     *                             its naming configuration is unusable
     */
    public static void validate(Policy policy) {
        PolicyType type = policy.getType();
        if (type != null) {
            validateScope(type, policy.getDataSetId());
        }
        validateNamingConfig(policy.getMetadata());
    }

    private static void validateScope(PolicyType type, Long dataSetId) {
        boolean attachedToDataSet = dataSetId != null;

        if (attachedToDataSet && !type.canAttachToDataSet()) {
            throw badRequest("Policy type " + type.name() + " is tenant-wide (" + type.getScope()
                    + ") and cannot be attached to a data set.", "dataSetId", String.valueOf(dataSetId));
        }
        if (!attachedToDataSet && !type.canApplyTenantWide()) {
            throw badRequest("Policy type " + type.name() + " applies per data set (" + type.getScope()
                    + ") and must be attached to one.", "dataSetId", "null");
        }
    }

    /**
     * A naming policy declaring the {@code pattern} preset must supply a usable pattern.
     *
     * <p>Only checked when the metadata actually says {@code kind=naming}; other policy kinds carry
     * their own config in the same map and must not be second-guessed here.
     */
    public static void validateNamingConfig(Map<String, String> metadata) {
        if (metadata == null || !NamingPolicy.KIND_NAMING.equals(metadata.get(NamingPolicy.KEY_KIND))) {
            return;
        }
        if (NamingPreset.parse(metadata.get(NamingPolicy.KEY_PRESET)) != NamingPreset.PATTERN) {
            return;
        }
        String problem = NamingPolicy.validatePattern(metadata.get(NamingPolicy.KEY_PATTERN));
        if (problem != null) {
            throw badRequest(problem, NamingPolicy.KEY_PATTERN,
                    String.valueOf(metadata.get(NamingPolicy.KEY_PATTERN)));
        }
    }

    private static BadRequestException badRequest(String message, String field, String value) {
        var error = new BadRequestError();
        error.setMessage(message);
        error.addFieldError(field, value);
        return new BadRequestException(new ResponseError<BadRequestError>().setError(error));
    }
}
