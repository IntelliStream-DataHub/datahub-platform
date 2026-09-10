package ai.intellistream.datahub.api.graphtransfer;

/**
 * The uploaded file is not a readable graph export: wrong magic bytes, an unsupported format
 * version, a corrupt gzip stream, or structurally impossible content. Maps to a 400.
 */
public class InvalidGraphFileException extends RuntimeException {

    public InvalidGraphFileException(String message) {
        super(message);
    }

    public InvalidGraphFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
