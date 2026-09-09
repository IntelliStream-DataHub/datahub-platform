// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.dto.INodeProxy;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FileSystemService {

    private final String FILENAME_REGEX = "[a-zA-Z0-9_.-]+";
    private final Pattern FILENAME_REGEX_PATTERN = Pattern.compile(FILENAME_REGEX);
    private final int MAX_FILENAME_LENGTH = 255;

    // Regex for an absolute path.
    // It should allow for paths like /foo/bar and /foo/bar/
    // ^/ : Must start with a forward slash.
    // (?:%s/?)* : Allows multiple segments like "folder/"
    // %s? : Allows for the last segment which might not have a trailing slash, or an empty string for just "/"
    private final Pattern ABSOLUTE_PATH_PATTERN;

    private final FilesConfig filesConfig;

    private final INodeRepository iNodeRepository;

    public FileSystemService(
            FilesConfig filesConfig,
            INodeRepository iNodeRepository
    ) {
        this.filesConfig = filesConfig;
        this.iNodeRepository = iNodeRepository;
        final String ABSOLUTE_PATH_REGEX_FORMAT = "^/(?:%s/)*(%s)?$";
        this.ABSOLUTE_PATH_PATTERN = Pattern.compile(String.format(ABSOLUTE_PATH_REGEX_FORMAT, FILENAME_REGEX, FILENAME_REGEX));
    }

    public boolean isValidFileAndFolderName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        if (name.length() > MAX_FILENAME_LENGTH) {
            return false;
        }

        Matcher matcher = FILENAME_REGEX_PATTERN.matcher(name);
        return matcher.matches();
    }

    // This method validates a path *after* it has been normalized by Path.normalize()
    private boolean isValidFolderPathInternal(String normalizedPathString) {
        if (normalizedPathString == null || normalizedPathString.isEmpty()) {
            return false;
        }
        if (!ABSOLUTE_PATH_PATTERN.matcher(normalizedPathString).matches()) {
            return false;
        }
        // A folder named like the upload-staging directory would collide with that staging area, so
        // the name is reserved (see TextValidator.RESERVED_TMP_DIR). Reject any path containing it.
        for (String segment : normalizedPathString.split("/")) {
            if (segment.equals(TextValidator.RESERVED_TMP_DIR)) {
                return false;
            }
        }
        return true;
    }

    public boolean validateFolderPath(Path path){
        if (path == null) {
            return false;
        }
        return validateFolderPath(path.toString());
    }

    public boolean isValidFolderPath(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }

        if (path.startsWith("/")) {
            path = path.substring(1);
        }



        Path normalizedPath = filesConfig.getRoot().resolve(path).normalize();
        if (!normalizedPath.startsWith(filesConfig.getRoot())) {
            return false;
        }
        if (Files.isDirectory(normalizedPath)) {
            return true;
        }
        return false;
    }

    // Kept this for string input, but it also uses Path.normalize internally
    public boolean validateFolderPath(String path){
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // The behavior of `resolve()` depends on whether the `other` path is
        // **absolute** or **relative**.
        if(path.startsWith("/")){
            path = path.substring(1);
        }

        Path normalizedPath = filesConfig.getRoot().resolve(path).normalize();

        // Ensure the normalized path is still within the root directory for validation
        // This handles cases like `path = "../"` or `path = "/../../"`
        if (!normalizedPath.startsWith(filesConfig.getRoot())) {
            return false;
        }

        // Now validate this combined and normalized path.
        // We need to validate its *logical* path representation relative to the root.
        // So, we'll get the portion of the path that is *relative* to filesConfig.getRoot()
        // and then validate that relative path against our internal regex, making it appear absolute.
        Path relativeToRoot = filesConfig.getRoot().relativize(normalizedPath);
        // Prepend '/' to make it logically absolute for isValidFolderPathInternal
        String relativePathString = "/" + relativeToRoot;

        var result = isValidFolderPathInternal(relativePathString);
        log.debug("isValidFolderPathInternal result: " + result);
        return result;
    }

    /**
     * Creates all nonexistent parent directories for the given file path.
     * If the parent directories already exist, no action is taken.
     *
     * @param filePath The Path instance representing the file.
     */
    public void createParentDirectories(Path filePath) {
        Path parentDirectory = filePath.getParent(); // Get the parent directory Path

        if (parentDirectory != null) {
            try {
                // Files.createDirectories() creates all nonexistent parent directories.
                // It does nothing if the directory already exists.
                Files.createDirectories(parentDirectory);
                log.error("Successfully ensured parent directories exist for: " + filePath);
            } catch (IOException e) {
                log.error("Failed to create parent directories for " + filePath + ": " + e.getMessage());
            }
        } else {
            log.error("The path " + filePath + " does not have a parent directory (it might be a root or current directory).");
        }
    }

    @Transactional
    public void moveNodeToTrash(INodeProxy inode) throws IOException {

        List<INodeProxy> nodesToMarkAsDeleted = new ArrayList<>();
        collectNodesRecursively(inode, nodesToMarkAsDeleted);
        sortINodes(nodesToMarkAsDeleted);

        // One instant for the whole subtree, so a folder and its contents record the same deletion
        // time rather than a spread of milliseconds across the loop.
        ZonedDateTime deletedAt = ZonedDateTime.now(ZoneOffset.UTC);
        for (INodeProxy nodeToMark : nodesToMarkAsDeleted) {
            // Move THIS node's filesystem object (not the top-level inode) to trash. The sort above
            // guarantees descendant files are processed before their containing folders, so each
            // folder is empty by the time it is removed.
            moveFileSystemObjectToTrash(nodeToMark, trashFileName(nodeToMark));
            log.debug("deleted inode {} at {}", nodeToMark.getId(), deletedAt);
            iNodeRepository.markDeleted(nodeToMark.getId(), deletedAt);
        }

    }

    /**
     * What a trashed node is called on disk: its numeric id.
     *
     * <p>This used to be the rewritten external id
     * ({@code DELETED_<checksum>_<originalId>_<epochMillis>}), which made a user-supplied string
     * part of a filesystem path and meant the upload had to guarantee it contained no path
     * separator. The id is generated, unique and unambiguous, so the trash layout no longer depends
     * on anything the caller sends.
     */
    static String trashFileName(INodeProxy node) {
        return String.valueOf(node.getId());
    }

    /**
     * Recursively collects an INode and all its descendants into a list.
     *
     * @param node The starting INode.
     * @param collectedNodes A list to store the collected nodes.
     */
    private void collectNodesRecursively(INodeProxy node, List<INodeProxy> collectedNodes) {
        collectedNodes.add(node);

        if (node.getNodeType() == INode.INodeType.FOLDER) {
            // Use the repository to find all direct children of the current folder
            List<INodeProxy> children = iNodeRepository.findLiveChildrenOf(node.getId());
            for (INodeProxy child : children) {
                // Recurse for each child
                collectNodesRecursively(child, collectedNodes);
            }
        }
    }

    /**
     * Moves the corresponding file or folder on the physical filesystem to the trash directory.
     *
     * @param node The INode representing the file or folder to move.
     * @throws IOException if the move operation fails.
     */
    private void moveFileSystemObjectToTrash(INodeProxy node, @NotNull String newFileName) throws IOException {
        Path sourcePath = Paths.get(filesConfig.getRoot().toString(), node.getPath())
                .toAbsolutePath()
                .normalize();
        Path trashPath = Paths.get(filesConfig.getTrash().toString(), newFileName)
                .toAbsolutePath()
                .normalize();

        // Do the filesystem op first, then let the caller mark the row deleted.
        // No Files.exists() pre-check: on NFS it lies (attribute cache), and
        // with multiple API instances it's a TOCTOU anyway. Tolerate missing
        // source idempotently so a retried delete doesn't fail the whole batch.
        if(node.getNodeType() == INode.INodeType.FILE){
            try {
                Files.createDirectories(trashPath.getParent());
                Files.move(sourcePath, trashPath);
                log.debug("Successfully moved file from '{}' to '{}'", sourcePath, trashPath);
            } catch (NoSuchFileException e) {
                log.warn("Source file already gone at delete time, continuing: {}", sourcePath);
            }
        } else if(node.getNodeType() == INode.INodeType.FOLDER){
            try{
                Files.delete(sourcePath);
                log.debug("Successfully deleted folder: '{}'", sourcePath);
            } catch (NoSuchFileException e) {
                log.warn("Source folder already gone at delete time, continuing: {}", sourcePath);
            } catch (DirectoryNotEmptyException e){
                log.error(e.getMessage());
            }
        }
    }

    @Transactional
    public void delete(Set<Long> idList, Set<String> externalIdHashes) throws IOException {
        List<INodeProxy> inodes = iNodeRepository.findAllByIdAndExternalIdAndNotDeleted(idList, externalIdHashes);
        sortINodes(inodes);

        for(INodeProxy inode : inodes){
            moveNodeToTrash(inode);
        }
    }

    private void sortINodes(List<INodeProxy> inodes) {
        // Sort by files first, then by inode level (desc)
        inodes.sort((i1, i2) -> {
            // Files first
            if (i1.getNodeType() == INode.INodeType.FILE && i2.getNodeType() != INode.INodeType.FILE) {
                return -1;
            }
            if (i1.getNodeType() != INode.INodeType.FILE && i2.getNodeType() == INode.INodeType.FILE) {
                return 1;
            }

            // For nodes of the same type category (file or non-file), sort by path depth descending.
            long depth1 = i1.getPath().chars().filter(ch -> ch == '/').count();
            long depth2 = i2.getPath().chars().filter(ch -> ch == '/').count();

            return Long.compare(depth2, depth1);
        });
    }

    /**
     * Restore soft-deleted FILES from the trash: recover the original external id from each node's
     * {@code DELETED_<checksum>_<origId>_<epoch>} name, move the file back from trash to its original
     * path, and clear {@code is_deleted}. Folders are not restorable in this version. Never overwrites:
     * throws {@link FileAlreadyExistsException} when the original path is taken and {@link
     * IllegalStateException} when the original external id is in use, the original folder is gone, or the
     * id can't be recovered — both mapped to 409 by the controller (like {@code update}'s move conflict).
     * Returns the restored nodes (carrying their original external id) for the response.
     */
    @Transactional
    public List<INode> restore(List<INode> nodes) throws IOException {
        List<Long> restoredIds = new ArrayList<>();
        for (INode node : nodes) {
            restoreOne(node);
            restoredIds.add(node.getId());
        }
        // markDeleted clears the persistence context (clearAutomatically), which detaches these
        // nodes — reading their lazy collections afterwards would throw. Re-load the restored rows
        // as managed entities so the controller's transformToIndexNode can read them inside the
        // transaction (the same contract the update endpoint relies on).
        return iNodeRepository.findAllById(restoredIds);
    }

    private void restoreOne(INode node) throws IOException {
        if (node.getNodeType() != INode.INodeType.FILE) {
            throw new IllegalStateException("Only files can be restored: '" + node.getName() + "'.");
        }
        // Pre-V47 tombstones still carry a rewritten external id and a trash file named after it.
        // Everything deleted since keeps its own id and is filed under its node id.
        String legacyOriginalId = recoverLegacyOriginalExternalId(node.getExternalId());
        boolean legacy = legacyOriginalId != null;
        String restoredExternalId = legacy ? legacyOriginalId : node.getExternalId();
        long restoredHash = legacy ? ExternalIds.hash(legacyOriginalId) : node.getExternalIdHash();

        // Uniqueness covers live rows only, so the id a tombstone holds may have been taken while it
        // sat in the trash. Refuse here rather than let the restore fail on the index.
        if (iNodeRepository.findByExternalIdHashAndDeletedAtIsNull(restoredHash, INode.class).isPresent()) {
            throw new IllegalStateException("A file with the original external id already exists.");
        }
        Path dest = Paths.get(filesConfig.getRoot().toString(), node.getPath()).toAbsolutePath().normalize();
        if (Files.exists(dest)) {
            throw new FileAlreadyExistsException(node.getPath());
        }
        Path destParent = dest.getParent();
        if (destParent == null || !Files.isDirectory(destParent)) {
            throw new IllegalStateException("The original folder no longer exists; cannot restore '" + node.getPath() + "'.");
        }
        String trashName = legacy ? node.getExternalId() : String.valueOf(node.getId());
        Path trashFile = Paths.get(filesConfig.getTrash().toString(), trashName).toAbsolutePath().normalize();
        Files.move(trashFile, dest); // throws FileAlreadyExistsException / IOException on failure
        // Disk moved back. For anything deleted since V47 clearing the time is the whole of the
        // database side; a legacy tombstone also has its original external id put back.
        if (legacy) {
            iNodeRepository.markRestoredFromLegacyTombstone(node.getId(), restoredExternalId, restoredHash);
        } else {
            iNodeRepository.markRestored(node.getId());
        }
    }


    /**
     * The original external id inside a pre-V47 tombstone, or null if this is not one.
     *
     * <p>Delete used to rewrite the external id to
     * {@code DELETED_<checksumHex>_<originalExternalId>_<epochMillis>} (files) or
     * {@code DELETED__<originalExternalId>_<epochMillis>} (folders); the original is everything
     * between the first and last underscore of the remainder, which is why an original containing
     * underscores survives. Nothing writes this shape any more — it exists so files already in the
     * trash when V47 ran can still be restored, and can go once none are left.
     */
    static String recoverLegacyOriginalExternalId(String externalId) {
        final String prefix = "DELETED_";
        if (externalId == null || !externalId.startsWith(prefix)) {
            return null;
        }
        String rest = externalId.substring(prefix.length()); // <checksum>_<originalId>_<epoch>
        int firstUnderscore = rest.indexOf('_');
        int lastUnderscore = rest.lastIndexOf('_');
        if (firstUnderscore < 0 || lastUnderscore <= firstUnderscore) {
            return null;
        }
        String original = rest.substring(firstUnderscore + 1, lastUnderscore);
        return original.isBlank() ? null : original;
    }

    /**
     * Rename ({@code newName}) and/or move ({@code newParentPath} with the already-resolved
     * {@code destParent}) a node. Moves the file or directory on disk, updates this node's
     * name/path, and for a folder rewrites the stored path of every descendant (the on-disk move
     * relocates the descendants physically). Pass {@code newParentPath == null} to keep the current
     * location (rename only); {@code destParent} is {@code null} for the root. Throws
     * {@link java.nio.file.FileAlreadyExistsException} when the target path is already taken.
     */
    @Transactional
    public INode renameOrMove(INode node, String newName, String newParentPath, INode destParent)
            throws IOException {
        String oldPath = node.getPath();
        String parentPath = (newParentPath != null) ? stripTrailingSlash(newParentPath) : parentPathOf(oldPath);
        String name = (newName != null && !newName.isBlank()) ? newName : node.getName();
        if (!isValidFileAndFolderName(name)) {
            throw new IllegalArgumentException("Invalid file or folder name: " + name);
        }
        String newPath = joinPath(parentPath, name);
        if (newPath.equals(oldPath)) {
            return node; // nothing to relocate
        }

        Path src = Paths.get(filesConfig.getRoot().toString(), oldPath).toAbsolutePath().normalize();
        Path dst = Paths.get(filesConfig.getRoot().toString(), newPath).toAbsolutePath().normalize();
        Files.createDirectories(dst.getParent());
        // No REPLACE_EXISTING: a taken target raises FileAlreadyExistsException, mapped to 409 above.
        Files.move(src, dst);

        if (newParentPath != null) {
            node.setParent(destParent);
        }
        node.setName(name);
        node.setPath(newPath); // setPath re-normalizes and recomputes pathHash
        if (node.getNodeType() == INode.INodeType.FOLDER) {
            rewriteDescendantPaths(node.getId(), oldPath, newPath);
        }
        return iNodeRepository.save(node);
    }

    /**
     * Assign {@code folder}'s dataset to every descendant that currently has no dataset, leaving
     * already-governed nodes (and their subtrees) untouched. A no-op for non-folders or a folder
     * with no dataset.
     */
    @Transactional
    public void cascadeDataSetToUnsetDescendants(INode folder) {
        if (folder.getNodeType() != INode.INodeType.FOLDER || folder.getDataSet() == null) {
            return;
        }
        cascadeDataSet(folder.getId(), folder.getDataSet());
    }

    private void cascadeDataSet(long folderId, NodeEntity dataSet) {
        for (INode child : iNodeRepository.findAllByParentId(folderId, INode.class)) {
            if (child.getDataSet() != null) {
                continue; // already governed — leave it and its subtree alone
            }
            child.setDataSet(dataSet);
            iNodeRepository.save(child);
            if (child.getNodeType() == INode.INodeType.FOLDER) {
                cascadeDataSet(child.getId(), dataSet);
            }
        }
    }

    /** Rewrite the stored path of every descendant after a folder's path changed. */
    private void rewriteDescendantPaths(long folderId, String oldPrefix, String newPrefix) {
        for (INode child : iNodeRepository.findAllByParentId(folderId, INode.class)) {
            boolean isFolder = child.getNodeType() == INode.INodeType.FOLDER;
            child.setPath(newPrefix + child.getPath().substring(oldPrefix.length()));
            iNodeRepository.save(child);
            if (isFolder) {
                rewriteDescendantPaths(child.getId(), oldPrefix, newPrefix);
            }
        }
    }

    private static String stripTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return (path.length() > 1 && path.endsWith("/")) ? path.substring(0, path.length() - 1) : path;
    }

    private static String parentPathOf(String path) {
        String p = stripTrailingSlash(path);
        int i = p.lastIndexOf('/');
        return (i <= 0) ? "/" : p.substring(0, i);
    }

    private static String joinPath(String parent, String name) {
        String p = stripTrailingSlash(parent);
        return (p.isEmpty() || p.equals("/")) ? "/" + name : p + "/" + name;
    }
}
