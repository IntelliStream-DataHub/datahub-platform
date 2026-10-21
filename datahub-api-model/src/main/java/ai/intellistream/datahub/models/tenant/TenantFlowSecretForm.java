// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * A secret's value, for {@code PUT /tenant/settings/flow/secrets/{name}}. The value is written and
 * never returned.
 *
 * @param value the secret, e.g. a password a flow's processor needs; required
 */
public record TenantFlowSecretForm(String value) {
}
