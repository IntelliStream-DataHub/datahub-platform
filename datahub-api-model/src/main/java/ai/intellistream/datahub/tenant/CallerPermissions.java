// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.tenant;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * What the caller of this request may do with the data in their tenant.
 *
 * <p>The companion to {@link TenantFeatures}: that says which features the <em>tenant</em> has,
 * this says what <em>you</em> may do with them. Clients need both to decide what to show — and,
 * for an AI agent, which tools are worth offering a model at all, since a tool the caller cannot
 * use only wastes context and produces a denial the model then has to reason about.
 *
 * <p><strong>This is not the enforcement boundary.</strong> Every read and write is still checked
 * server-side on the request that performs it. Narrowing a UI (or a tool list) from this is a
 * convenience; it grants nothing and skipping it takes nothing away.
 *
 * @param readAll            the caller may read every dataset — the {@code /datasets/*&#47;read}
 *                           organization group, or {@code DATAHUB_ADMIN}
 * @param writeAll           the caller may write every dataset. Independent of {@code readAll}:
 *                           a write grant does not imply read, deliberately, so an ingest
 *                           identity can write without reading back
 * @param canManageDataSets  the caller may create, update or delete datasets themselves, as
 *                           opposed to the data inside them. Requires an all-datasets write
 *                           grant, because renaming or re-parenting a dataset changes what every
 *                           existing grant covers
 * @param readableDataSetIds datasets the caller may read, already expanded down the
 *                           {@code BELONGS_TO} hierarchy. <strong>Empty means "read everything or
 *                           nothing"</strong> — read {@code readAll} first; empty with
 *                           {@code readAll} false means no access at all
 * @param writableDataSetIds datasets the caller may write, same expansion and same caveat
 *                           against {@code writeAll}
 */
@Schema(name = "CallerPermissions",
        description = """
                What the caller may do with data in their tenant. Use it to gate UI and to decide
                which operations are worth offering. Empty id sets mean "everything or nothing" —
                check the corresponding readAll/writeAll flag first. Not an enforcement boundary:
                every request is checked server-side regardless.""")
public record CallerPermissions(
        @Schema(description = "The caller may read every dataset in the tenant.")
        boolean readAll,

        @Schema(description = "The caller may write every dataset. Does not imply readAll.")
        boolean writeAll,

        @Schema(description = "The caller may create, update or delete datasets themselves.")
        boolean canManageDataSets,

        @Schema(description = "Readable dataset ids, hierarchy-expanded. Empty when readAll, "
                + "and also when the caller can read nothing.")
        Set<Long> readableDataSetIds,

        @Schema(description = "Writable dataset ids, hierarchy-expanded. Empty when writeAll, "
                + "and also when the caller can write nothing.")
        Set<Long> writableDataSetIds) {

    /**
     * True when the caller can read no dataset at all — neither everything nor anything named.
     *
     * <p>Worth its own method because the two fields have to be read together to mean anything:
     * an empty id set on its own reads as "no access" but is exactly what an administrator with
     * access to everything also has.
     */
    public boolean canReadNothing() {
        return !readAll && (readableDataSetIds == null || readableDataSetIds.isEmpty());
    }

    /** True when the caller can write no dataset at all. See {@link #canReadNothing()}. */
    public boolean canWriteNothing() {
        return !writeAll && (writableDataSetIds == null || writableDataSetIds.isEmpty());
    }
}
