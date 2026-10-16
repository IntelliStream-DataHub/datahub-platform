// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

import java.util.List;

/**
 * The credentials your tenant's flows may use, as returned by {@code GET /tenant/settings/flow}.
 *
 * <p>Names only. A secret's value and a client's secret are written through this API and never
 * read back through it; the flow service reads them from Vault when a run starts. A client id is
 * returned because it is not a credential, and it is what tells a person which Keycloak client a
 * name stands for.
 *
 * @param secrets the stored secrets' names, sorted, which a flow refers to by name
 * @param clients the registered clients a flow's runs may act as, sorted by name
 */
public record TenantFlowSettings(List<String> secrets, List<Client> clients) {

    /**
     * A registered client: a confidential Keycloak client the tenant created, whose service-account
     * user holds the organization groups the flows acting as it should have.
     *
     * @param name     what a deployment names it by ({@code runAs})
     * @param clientId its Keycloak client id
     */
    public record Client(String name, String clientId) {
    }
}
