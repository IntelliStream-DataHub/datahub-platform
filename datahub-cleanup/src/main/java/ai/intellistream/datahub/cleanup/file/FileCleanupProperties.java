// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.file;

import ai.intellistream.datahub.helpers.text.TextValidator;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the file-cleanup jobs, bound from {@code datahub.cleanup.file.*}.
 */
@Data
@ConfigurationProperties(prefix = "datahub.cleanup.file")
public class FileCleanupProperties {

    /**
     * Name of the per-folder upload temp directory. Defaults to {@link
     * TextValidator#RESERVED_TMP_DIR} — the single shared constant the API's upload path
     * (FileController) also uses — so the upload writer and this cleaner can never drift.
     * Overridable only for tests/migration.
     */
    private String tempDirName = TextValidator.RESERVED_TMP_DIR;

    /** Upload temp entries older than this are deleted. */
    private Duration staleUploadAge = Duration.ofDays(3);

    /** Directory (created under each storage base) holding quarantined former-tenant folders. */
    private String quarantineDirName = "_orphaned";

    /** Quarantined folders older than this are permanently deleted. */
    private Duration quarantineGrace = Duration.ofDays(30);

    /**
     * How long a soft-deleted (trashed) file/folder is retained before the {@code DeletedFilePurgeTask}
     * permanently deletes it — the retention window a user has to restore it. Keyed off the deletion
     * epoch embedded in the trashed inode's {@code external_id} ({@code DELETED_..._<epochMillis>}).
     * Default 30 days.
     */
    private Duration deletedFileGrace = Duration.ofDays(30);

    /**
     * When {@code true}, the file-cleanup jobs only <em>log</em> the entries they would quarantine
     * or delete and never touch the filesystem. Defaults to {@code false} (these jobs delete for
     * real), but the {@code dev} profile flips it on so local runs are non-destructive — see
     * {@code application-dev.properties}. Override with {@code datahub.cleanup.file.dry-run}.
     */
    private boolean dryRun = false;
}
