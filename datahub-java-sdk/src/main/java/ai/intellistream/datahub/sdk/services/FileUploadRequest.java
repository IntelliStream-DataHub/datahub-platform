// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

/**
 * A file to upload: the {@code content} bytes plus metadata. {@code path} (the destination) and
 * {@code content} are required; the rest are optional. Built with {@link #builder()}.
 */
public final class FileUploadRequest {

    private final String path;
    private final byte[] content;
    private final String externalId;
    private final Long dataSetId;
    private final String description;
    private final String source;
    private final String contentType;

    private FileUploadRequest(Builder b) {
        if (b.path == null || b.path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (b.content == null) {
            throw new IllegalArgumentException("content is required");
        }
        this.path = b.path;
        this.content = b.content;
        this.externalId = b.externalId;
        this.dataSetId = b.dataSetId;
        this.description = b.description;
        this.source = b.source;
        this.contentType = b.contentType == null ? "application/octet-stream" : b.contentType;
    }

    public String path()        { return path; }
    public byte[] content()     { return content; }
    public String externalId()  { return externalId; }
    public Long dataSetId()     { return dataSetId; }
    public String description() { return description; }
    public String source()      { return source; }
    public String contentType() { return contentType; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String path;
        private byte[] content;
        private String externalId;
        private Long dataSetId;
        private String description;
        private String source;
        private String contentType;

        public Builder path(String path)               { this.path = path; return this; }
        public Builder content(byte[] content)         { this.content = content; return this; }
        public Builder externalId(String externalId)   { this.externalId = externalId; return this; }
        public Builder dataSetId(Long dataSetId)       { this.dataSetId = dataSetId; return this; }
        public Builder description(String description)  { this.description = description; return this; }
        public Builder source(String source)           { this.source = source; return this; }
        public Builder contentType(String contentType) { this.contentType = contentType; return this; }

        public FileUploadRequest build() {
            return new FileUploadRequest(this);
        }
    }
}
