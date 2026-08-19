package ai.intellistream.datahub.api.graphtransfer;

import java.util.List;
import java.util.Map;

/**
 * The portable content of an exported resource graph — everything needed to recreate the
 * component in another tenant or environment. Numeric ids are deliberately absent: node ids are
 * database identities that do not survive a transfer, so nodes are keyed by {@code externalId}
 * and relations reference their endpoints the same way. The dataset a node belongs to is carried
 * as {@code dataSetExternalId} for the same reason, resolvable only when the dataset node itself
 * is part of the export or already exists at the import side.
 *
 * <p>Serialized by {@link GraphFileCodec}.
 */
public record GraphExportFile(List<ExportedNode> nodes, List<ExportedRelation> relations) {

    public record ExportedNode(
            String externalId,
            String name,
            String description,
            String source,
            boolean isRoot,
            String geoJson,
            String dataSetExternalId,
            List<String> labels,
            Map<String, String> metadata) {
    }

    public record ExportedRelation(
            String fromExternalId,
            String toExternalId,
            String type,
            String description,
            String dataSetExternalId,
            Map<String, String> metadata) {
    }
}
