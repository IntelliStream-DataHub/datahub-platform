// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Soft-delete {@code roots} and everything beneath them. Each file moves to
     * {@code <trash>/<id>} and each folder is removed from disk (it is empty by then); every row gets
     * {@code deleted_at} and keeps its external id, since uniqueness covers live rows only.
     *
     * <p>Takes managed entities: the caller's transaction carries the row changes. The disk steps
     * cannot join it, so each is undone if it rolls back — whether a later move failed or the commit
     * did — rather than leaving files in the trash under live rows.
     */
    @Transactional(rollbackFor = IOException.class)
    public void delete(List<INode> roots) throws IOException {
        // A folder and something inside it may both be named; each node is handled once.
        Map<Long, INode> byId = new LinkedHashMap<>();
        for (INode root : roots) {
            collectNodesRecursively(root, byId);
        }
        List<INode> nodes = new ArrayList<>(byId.values());
        sortINodes(nodes);

        List<DiskStep> undo = undoOnRollback();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        for (INode node : nodes) {
            // Descendant files come before their folders (sortINodes), so each folder is empty here.
            boolean isFile = node.getNodeType() == INode.INodeType.FILE;
            String trashName = isFile ? String.valueOf(node.getId()) : null;
            moveFileSystemObjectToTrash(node, trashName, undo);
            node.setDeletedAt(now);
            node.setTrashName(trashName);
        }
    }

    private void collectNodesRecursively(INode node, Map<Long, INode> collected) {
        if (collected.putIfAbsent(node.getId(), node) != null) {
            return;
        }
        if (node.getNodeType() == INode.INodeType.FOLDER) {
            for (INode child : iNodeRepository.findAllByParentAndDeletedAtIsNull(node, INode.class)) {
                collectNodesRecursively(child, collected);
            }
        }
    }

    /** Move a file to {@code <trash>/<trashName>}, or remove a folder, recording how to undo it. */
    private void moveFileSystemObjectToTrash(INode node, String trashName, List<DiskStep> undo) throws IOException {
        Path sourcePath = Paths.get(filesConfig.getRoot().toString(), node.getPath())
                .toAbsolutePath()
                .normalize();

        // No Files.exists() pre-check: on NFS it lies (attribute cache), and with multiple API
        // instances it's a TOCTOU anyway. Tolerate a missing source so a retried delete doesn't fail
        // the whole batch.
        if (node.getNodeType() == INode.INodeType.FILE) {
            Path trashPath = filesConfig.getTrash().resolve(trashName).toAbsolutePath().normalize();
            try {
                Files.createDirectories(trashPath.getParent());
                Files.move(sourcePath, trashPath);
                undo.add(new DiskStep(sourcePath, trashPath));
                log.debug("Moved file '{}' to '{}'", sourcePath, trashPath);
            } catch (NoSuchFileException e) {
                log.warn("Source file already gone at delete time, continuing: {}", sourcePath);
            }
        } else if (node.getNodeType() == INode.INodeType.FOLDER) {
            try {
                Files.delete(sourcePath);
                undo.add(new DiskStep(sourcePath, null));
                log.debug("Deleted folder '{}'", sourcePath);
            } catch (NoSuchFileException e) {
                log.warn("Source folder already gone at delete time, continuing: {}", sourcePath);
            } catch (DirectoryNotEmptyException e) {
                log.error(e.getMessage());
            }
        }
    }

    private void sortINodes(List<INode> inodes) {
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
     * Restore soft-deleted FILES: move each back from the trash to its original path and set
     * {@code deleted_at} back to null. Folders are not restorable in this version. Never overwrites: throws
     * {@link FileAlreadyExistsException} when the original path is taken and
     * {@link RestoreRefusedException} when the external id is in use by a live node, the original
     * folder is gone, or the trash entry is unknown — all mapped to 409 by the controller. Takes
     * managed entities and returns them restored.
     */
    @Transactional(rollbackFor = IOException.class)
    public List<INode> restore(List<INode> nodes) throws IOException {
        List<DiskStep> undo = undoOnRollback();
        for (INode node : nodes) {
            restoreOne(node, undo);
        }
        return nodes;
    }

    private void restoreOne(INode node, List<DiskStep> undo) throws IOException {
        if (node.getNodeType() != INode.INodeType.FILE) {
            throw new RestoreRefusedException("not-a-file", "Only files can be restored, not folders.");
        }
        if (node.getTrashName() == null) {
            throw new RestoreRefusedException("trash-entry-missing", "The file has no entry in the trash.");
        }
        // Delete frees the external id for reuse; refuse if a live node has since taken it. The query
        // flushes earlier restores in this batch first, so two trashed copies of one id can't both return.
        if (iNodeRepository.findByExternalIdHashAndDeletedAtIsNull(node.getExternalIdHash(), INode.class).isPresent()) {
            throw new RestoreRefusedException("external-id-taken", "A file with the same external id already exists.");
        }
        Path dest = Paths.get(filesConfig.getRoot().toString(), node.getPath()).toAbsolutePath().normalize();
        if (Files.exists(dest)) {
            throw new FileAlreadyExistsException(node.getPath());
        }
        Path destParent = dest.getParent();
        if (destParent == null || !Files.isDirectory(destParent)) {
            throw new RestoreRefusedException("folder-missing", "The file's original folder no longer exists.");
        }
        Path trash = filesConfig.getTrash().toAbsolutePath().normalize();
        Path trashFile = trash.resolve(node.getTrashName()).normalize();
        if (!trashFile.startsWith(trash)) {
            throw new RestoreRefusedException("trash-entry-missing", "The file has no entry in the trash.");
        }
        Files.move(trashFile, dest); // throws FileAlreadyExistsException / IOException on failure
        undo.add(new DiskStep(trashFile, dest));
        node.setDeletedAt(null);
        node.setTrashName(null);
    }

    /** One reversible disk step: a move {@code from → to}, or the removal of folder {@code from} ({@code to} null). */
    private record DiskStep(Path from, Path to) {}

    /**
     * A list the caller appends its disk steps to; if the current transaction rolls back they are
     * reversed, newest first, so disk returns to where the database is.
     */
    private List<DiskStep> undoOnRollback() {
        List<DiskStep> steps = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                for (DiskStep step : steps.reversed()) {
                    try {
                        if (step.to() == null) {
                            Files.createDirectories(step.from());
                        } else {
                            Files.move(step.to(), step.from());
                        }
                    } catch (IOException e) {
                        log.error("Could not undo disk step {} after rollback: {}", step, e.getMessage());
                    }
                }
            }
        });
        return steps;
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

    /** A restore refused by the state of the tree; {@code reason} is a stable token, the message names no file. */
    public static class RestoreRefusedException extends IllegalStateException {
        private final String reason;

        public RestoreRefusedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

}
