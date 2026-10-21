// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * A registered client's credentials, for {@code PUT /tenant/settings/flow/clients/{name}}.
 *
 * <p>Both are required, every time. The client secret is never returned, so a client is replaced
 * by sending both again rather than by changing one half: a pair stored half old and half new would
 * fail only when a run first tries it.
 *
 * @param clientId     the confidential Keycloak client's id
 * @param clientSecret its secret; written and never returned
 */
public record TenantFlowClientForm(String clientId, String clientSecret) {
}
