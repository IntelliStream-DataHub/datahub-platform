// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code datahub.files.upload.*} for {@code PUT /files}.
 *
 * <p>{@code buffer-size} is the read/write/hash block size: the upload reads the request body into
 * a buffer this big before each write and hash, so larger blocks mean fewer, larger writes (and on
 * NFS, fewer, larger WRITE RPCs). The 1&nbsp;MB default lines up with a typical NFS {@code wsize}.
 */
@Component
@ConfigurationProperties(prefix = "datahub.files.upload")
public class UploadProperties {

    private int bufferSize = 1 << 20;        // 1 MB

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }
}
