// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.TreeSet;
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
 * Paths are relative to the organization, which is what Keycloak emits in the
 * {@code organization.&lt;alias&gt;.groups} claim, so the tenant is already implicit — including for
 * the {@code *} wildcard: "all datasets" always means all datasets <em>of this organization</em>.
 * That is why the all-datasets grants live here rather than as realm roles: a realm role travels on
 * every token a multi-organization user can mint, which made "all datasets" quietly span tenants.
 * The only realm role left in the dataset ACL is {@code DATAHUB_ADMIN}, the deliberately
 * cross-tenant operator escape hatch.
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
 *
 * <h2>Unrecognised paths</h2>
 * Silently ignored. An organization's group tree is theirs, and may well hold groups that have
 * nothing to do with DataHub; those must not be errors. A path that looks like it was *meant* as a
 * grant but is malformed is logged at debug.
 */
@Slf4j
public record DatasetGrants(boolean readAll, boolean writeAll,
                            Set<String> readExternalIds, Set<String> writeExternalIds) {

    private static final String PREFIX = "/datasets/";
    private static final String ALL_DATASETS = "*";
    private static final String READ = "read";
    private static final String WRITE = "write";

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
        if (groupPaths == null || groupPaths.isEmpty()) {
            return NONE;
        }
        boolean readAll = false;
        boolean writeAll = false;
        // TreeSet with a case-insensitive comparator, not a LinkedHashSet: external ids are unique
        // ignoring case, so "/datasets/data_set_sap/read" and "/datasets/Data_Set_SAP/read" name the
        // same data set and must collapse to one grant. They would otherwise both survive, resolve
        // to the same dataset anyway, and only differ in producing two closure cache entries for one
        // grant set. Sorted order also makes the cache fingerprint stable regardless of the order
        // the identity provider returns the groups in.
        Set<String> read = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> write = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (String path : groupPaths) {
            if (path == null || !path.startsWith(PREFIX)) {
                continue;
            }
            String remainder = path.substring(PREFIX.length());
            int split = remainder.lastIndexOf('/');
            if (split <= 0 || split == remainder.length() - 1) {
                // "/datasets/foo" (a container group, no permission) or a trailing slash.
                log.debug("Ignoring organization group with no permission segment: {}", path);
                continue;
            }
            String externalId = remainder.substring(0, split);
            String permission = remainder.substring(split + 1);

            if (externalId.indexOf('/') >= 0) {
                // Deeper than the grammar allows, e.g. /datasets/a/b/read. Refusing rather than
                // guessing keeps an unintended nesting from silently granting something.
                log.debug("Ignoring organization group nested deeper than the grant grammar: {}", path);
                continue;
            }

            if (ALL_DATASETS.equals(externalId)) {
                switch (permission.toLowerCase()) {
                    case READ -> readAll = true;
                    case WRITE -> writeAll = true;
                    default -> log.debug("Ignoring organization group with unknown permission '{}': {}",
                            permission, path);
                }
                continue;
            }

            // Taken verbatim. This used to snake_case the segment, which was invisible while every
            // dataset external id was snake_case anyway — and became a silent access bug the moment
            // ids were stored verbatim: a group "/datasets/COM-99-PT-1034/read" would be rewritten
            // to "com_99_pt_1034", match no dataset, and deny access that had been granted. Case is
            // still not significant here, because the lookup hashes through ExternalIds.hash().
            if (externalId.isBlank()) {
                continue;
            }

            switch (permission.toLowerCase()) {
                case READ -> read.add(externalId);
                case WRITE -> write.add(externalId);
                default -> log.debug("Ignoring organization group with unknown permission '{}': {}",
                        permission, path);
            }
        }

        if (!readAll && !writeAll && read.isEmpty() && write.isEmpty()) {
            return NONE;
        }
        return new DatasetGrants(readAll, writeAll,
                Collections.unmodifiableSet(read), Collections.unmodifiableSet(write));
    }
}
