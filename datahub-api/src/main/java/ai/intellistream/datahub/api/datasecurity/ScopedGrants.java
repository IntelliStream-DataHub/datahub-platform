// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read and write grants over named subjects, parsed out of Keycloak organization group paths.
 *
 * <h2>Grammar</h2>
 * <pre>
 *   /&lt;prefix&gt;/&lt;subject&gt;/read
 *   /&lt;prefix&gt;/&lt;subject&gt;/write
 *   /&lt;prefix&gt;/*&#47;read           (every subject under this prefix)
 *   /&lt;prefix&gt;/*&#47;write
 * </pre>
 *
 * <p>Paths are relative to the organization, which is what Keycloak emits in the
 * {@code organization.<alias>.groups} claim, so the tenant is already implicit — including for the
 * {@code *} wildcard, which always means all subjects <em>of this organization</em>. That is why
 * blanket grants live here rather than as realm roles: a realm role travels on every token a
 * multi-organization user can mint, which made "everything" quietly span tenants.
 *
 * <p>Read and write are independent. A {@code write} grant does not imply {@code read}, and the
 * wildcard follows the same rule.
 *
 * <p>Subjects are matched case-insensitively but stored verbatim. Nothing else about the segment is
 * adjusted: an administrator names the subject as it is stored, which is the contract every other
 * reference to it follows.
 *
 * <h2>Unrecognised paths</h2>
 * Silently ignored. An organization's group tree is theirs and may hold groups that have nothing to
 * do with DataHub; those must not be errors. A path that looks like it was <em>meant</em> as a
 * grant but is malformed is logged at debug.
 *
 * <p>Extracted from {@link DatasetGrants}, which was the only user, when settings needed the same
 * grammar. Two copies of a parser that decides who may read what is one copy too many: a fix to one
 * would not reach the other, and the difference would be invisible until it granted something.
 */
@Slf4j
public record ScopedGrants(boolean readAll, boolean writeAll,
                           Set<String> readSubjects, Set<String> writeSubjects) {

    private static final String ALL = "*";
    private static final String READ = "read";
    private static final String WRITE = "write";

    private static final ScopedGrants NONE =
            new ScopedGrants(false, false, Collections.emptySet(), Collections.emptySet());

    public static ScopedGrants none() {
        return NONE;
    }

    public boolean isEmpty() {
        return !readAll && !writeAll && readSubjects.isEmpty() && writeSubjects.isEmpty();
    }

    public boolean canRead(String subject) {
        return readAll || readSubjects.contains(subject);
    }

    public boolean canWrite(String subject) {
        return writeAll || writeSubjects.contains(subject);
    }

    /**
     * @param prefix the path segment owning these grants, without slashes — {@code datasets},
     *               {@code settings}
     */
    public static ScopedGrants from(Collection<String> groupPaths, String prefix) {
        if (groupPaths == null || groupPaths.isEmpty()) {
            return NONE;
        }
        String root = "/" + prefix + "/";
        boolean readAll = false;
        boolean writeAll = false;
        // TreeSet with a case-insensitive comparator, not a LinkedHashSet: subjects are unique
        // ignoring case, so "/datasets/data_set_sap/read" and "/datasets/Data_Set_SAP/read" name the
        // same subject and must collapse to one grant. They would otherwise both survive, resolve to
        // the same thing anyway, and only differ in producing two closure cache entries for one
        // grant set. Sorted order also makes the cache fingerprint stable regardless of the order
        // the identity provider returns the groups in.
        Set<String> read = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> write = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (String path : groupPaths) {
            if (path == null || !path.startsWith(root)) {
                continue;
            }
            String remainder = path.substring(root.length());
            int split = remainder.lastIndexOf('/');
            if (split <= 0 || split == remainder.length() - 1) {
                // "/datasets/foo" (a container group, no permission) or a trailing slash.
                log.debug("Ignoring organization group with no permission segment: {}", path);
                continue;
            }
            String subject = remainder.substring(0, split);
            String permission = remainder.substring(split + 1);

            if (subject.indexOf('/') >= 0) {
                // Deeper than the grammar allows, e.g. /datasets/a/b/read. Refusing rather than
                // guessing keeps an unintended nesting from silently granting something.
                log.debug("Ignoring organization group nested deeper than the grant grammar: {}", path);
                continue;
            }
            if (subject.isBlank()) {
                continue;
            }

            boolean all = ALL.equals(subject);
            switch (permission.toLowerCase()) {
                case READ -> {
                    if (all) {
                        readAll = true;
                    } else {
                        read.add(subject);
                    }
                }
                case WRITE -> {
                    if (all) {
                        writeAll = true;
                    } else {
                        write.add(subject);
                    }
                }
                default -> log.debug("Ignoring organization group with unknown permission '{}': {}",
                        permission, path);
            }
        }

        if (!readAll && !writeAll && read.isEmpty() && write.isEmpty()) {
            return NONE;
        }
        return new ScopedGrants(readAll, writeAll,
                Collections.unmodifiableSet(read), Collections.unmodifiableSet(write));
    }
}
