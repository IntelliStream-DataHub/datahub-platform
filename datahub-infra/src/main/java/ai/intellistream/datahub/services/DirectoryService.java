// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DirectoryService {

    private final INodeRepository iNodeRepository;
    private final FileSystemService fileSystemService;
    private final Pattern NORMALIZE_SLASHES_PATTERN = Pattern.compile("/+");

    public DirectoryService(INodeRepository iNodeRepository, FileSystemService fileSystemService) {
        this.iNodeRepository = iNodeRepository;
        this.fileSystemService = fileSystemService;
    }

    @Transactional
    public INode createDirectoriesFromPath(@NotNull String datahubFolderPath) {
        return createDirectoriesFromPath(datahubFolderPath, null);
    }

    /**
     * Create the folder hierarchy for {@code datahubFolderPath}, assigning {@code dataSet} to every
     * folder created along the way. Folders inherit the dataset of the file being uploaded into them
     * so the same dataset ACL that gates files also gates their containing folders. A {@code null}
     * dataset produces "public" folders (no dataset → visible to everyone). Pre-existing folders on
     * the path keep whatever dataset they already had.
     */
    @Transactional
    public INode createDirectoriesFromPath(@NotNull String datahubFolderPath, NodeEntity dataSet) {
        // Validate the full path using FileSystemService
        if (!fileSystemService.validateFolderPath(datahubFolderPath)) {
            throw new IllegalArgumentException("Invalid directory path: " + datahubFolderPath);
        }
        // Normalize the path (remove leading/trailing slashes, handle multiple slashes)
        String normalizedPath = normalizePath(datahubFolderPath);

        return createDirectoryRecursive(normalizedPath, dataSet);
    }

    @Transactional
    protected INode createDirectoryRecursive(@NotNull String currentFullPath, NodeEntity dataSet) {
        // Base Case: If the currentFullPath is empty, it means we are at the conceptual root.
        // In your system, the root might not have a corresponding Directory entity, or it's implicitly handled.
        // For this implementation, an empty path means no directory to create/find.
        if (currentFullPath.isEmpty()) {
            return null; // Null is the root directory, which is handled by the caller of this method
        }

        // Check if this folder already exists in the database
        long pathHash = IdGenerator.xxHash(currentFullPath);
        Optional<INode> maybeFolder = iNodeRepository
                .findByPathHashAndNodeType(pathHash, INode.INodeType.FOLDER, INode.class);

        if (maybeFolder.isPresent()) {
            return maybeFolder.get();
        } else {
            // Determine the name of the current directory (last segment of the path)
            int lastSlashIndex = currentFullPath.lastIndexOf('/');
            String currentDirectoryName;
            String parentPath;

            if (lastSlashIndex == -1) { // No slash, means it's a top-level directory
                currentDirectoryName = currentFullPath;
                parentPath = ""; // No parent path, so its parent will be null (root)
            } else {
                currentDirectoryName = currentFullPath.substring(lastSlashIndex + 1);
                parentPath = currentFullPath.substring(0, lastSlashIndex);
            }

            // Recursively ensure the parent directory exists
            // The result of this recursive call will be the parent Directory object
            INode createdParent = createDirectoryRecursive(parentPath, dataSet);

            // Create the current directory
            INode newFolder = new INode();
            newFolder.setName(currentDirectoryName);
            newFolder.setExternalId("datahub-folder-" + currentDirectoryName);
            newFolder.setNodeType(INode.INodeType.FOLDER);
            newFolder.setPath(currentFullPath);
            newFolder.setPathHash(pathHash);
            newFolder.setParent(createdParent); // Link to the parent that was found or created
            // Inherit the uploading file's dataset so folder visibility/writes follow the same ACL.
            newFolder.setDataSet(dataSet);
            return iNodeRepository.save(newFolder);
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        // Replace multiple slashes with a single slash
        String normalized = NORMALIZE_SLASHES_PATTERN.matcher(path).replaceAll("/");

        // Remove trailing slash if present
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

}
