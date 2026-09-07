// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * The dataset access a caller is granted, parsed out of their Keycloak organization group paths.
 *
 * <h2>Grammar</h2>
 * <pre>
 *   /datasets/&lt;externalId&gt;/read
 *   /datasets/&lt;externalId&gt;/write
 *   /datasets/*&#47;read          (every dataset in the organization)
 *   /datasets/*&#47;write
 * </pre>
 * One instance of the shared {@link GrantGrammar}, which owns the parsing rules; this class adds
 * the dataset meaning. The tenant is implicit — the paths come from the organization's own group
 * tree — including for the {@code *} wildcard: "all datasets" always means all datasets <em>of
 * this organization</em>. That is why the all-datasets grants live here rather than as realm
 * roles: a realm role travels on every token a multi-organization user can mint, which made "all
 * datasets" quietly span tenants. The only realm role left in the dataset ACL is
 * {@code DATAHUB_ADMIN}, the deliberately cross-tenant operator escape hatch.
 *
 * <p>{@code *} cannot collide with a real dataset: external ids are restricted to
 * {@code [A-Za-z0-9._:+=-]+}, which does not admit an asterisk.
 *
 * <p>Read and write are independent, matching the rest of the dataset ACL: a {@code write} grant
 * does not imply {@code read}, and the wildcard follows the same rule.
 *
 * <h2>The external id segment is taken verbatim</h2>
 * The group must name the data set's actual external id. Case is not significant — the lookup hashes
 * through {@code ExternalIds.hash}, which folds case exactly as uniqueness does — but nothing else
 * is adjusted.
 *
 * <p>This segment used to be snake_cased, which was invisible while every dataset external id was
 * snake_case anyway, and became a silent <em>access</em> bug the moment ids were stored verbatim: a
 * group {@code /datasets/COM-99-PT-1034/read} would be rewritten to {@code com_99_pt_1034}, match no
 * data set, and quietly deny access that had been granted. The rewrite was also doing undeclared
 * double duty, letting a group named {@code Data Set SAP} match {@code data_set_sap} — a coincidence
 * of the old naming rule rather than a designed behaviour. An administrator now names the data set
 * as it is stored, which is the same contract every other reference to an external id follows.
 */
public record DatasetGrants(boolean readAll, boolean writeAll,
                            Set<String> readExternalIds, Set<String> writeExternalIds) {

    private static final String READ = "read";
    private static final String WRITE = "write";
    /** A new verb on datasets starts here, and gets surfaced as accessors alongside read/write. */
    private static final GrantGrammar GRAMMAR = GrantGrammar.of("datasets", READ, WRITE);

    private static final DatasetGrants NONE =
            new DatasetGrants(false, false, Collections.emptySet(), Collections.emptySet());

    public static DatasetGrants none() {
        return NONE;
    }

    public boolean isEmpty() {
        return !readAll && !writeAll && readExternalIds.isEmpty() && writeExternalIds.isEmpty();
    }

    /** Parse organization group paths into the dataset access they grant. */
    public static DatasetGrants from(Collection<String> groupPaths) {
        GrantGrammar.Grants grants = GRAMMAR.parse(groupPaths);
        if (grants.isEmpty()) {
            return NONE;
        }
        return new DatasetGrants(grants.allowsAll(READ), grants.allowsAll(WRITE),
                grants.objects(READ), grants.objects(WRITE));
    }
}
