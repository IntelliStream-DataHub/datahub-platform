package ai.intellistream.datahub.api.graphtransfer;

/**
 * The graph or file is over a transfer limit: too many nodes/relations to export or import, or an
 * upload bigger than the byte caps allow. Not corruption ({@link InvalidGraphFileException}) — the
 * content may be perfectly valid, it is just larger than this endpoint is willing to move.
 */
public class GraphTransferLimitException extends RuntimeException {

    public GraphTransferLimitException(String message) {
        super(message);
    }
}
