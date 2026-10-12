// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.access;

import java.util.Set;

/**
 * What the caller of a request may do with flows, as datahub-api answered for their token.
 *
 * <p>Seeing a flow takes {@code /flows/read} and read access to its dataset; changing it takes
 * {@code /flows/write} and write access. {@code DATAHUB_ADMIN} may do everything. The flow service
 * never evaluates dataset grants itself: {@link #read} and {@link #write} are what
 * {@code GET /me/datasets} returned, after the api expanded them down the dataset hierarchy.
 *
 * @param subject the token's {@code sub}, recorded as who created or changed a flow
 */
public record Caller(String subject, boolean admin, boolean flowsRead, boolean flowsWrite,
                     DataSets read, DataSets write) {

    public static final String FLOWS_READ = "/flows/read";
    public static final String FLOWS_WRITE = "/flows/write";

    public boolean canRead(long dataSetId) {
        return admin || (flowsRead && read.contains(dataSetId));
    }

    public boolean canWrite(long dataSetId) {
        return admin || (flowsWrite && write.contains(dataSetId));
    }

    /**
     * Whether the caller sees the flows whose dataset was deleted: nobody can through a grant on a
     * dataset that is gone, so they are shown to whoever may write every dataset, to move them to
     * another dataset or delete them.
     */
    public boolean seesOrphans() {
        return admin || (flowsRead && write.all());
    }

    /** The datasets the caller has one kind of access to: all of them, or these. */
    public record DataSets(boolean all, Set<Long> ids) {

        public static final DataSets ALL = new DataSets(true, Set.of());

        public DataSets {
            ids = Set.copyOf(ids);
        }

        public boolean contains(long dataSetId) {
            return all || ids.contains(dataSetId);
        }
    }
}
