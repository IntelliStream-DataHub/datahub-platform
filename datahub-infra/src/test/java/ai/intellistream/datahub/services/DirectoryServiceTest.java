// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirectoryServiceTest {

    @Mock private INodeRepository iNodeRepository;
    @Mock private FileSystemService fileSystemService;

    @InjectMocks private DirectoryService directoryService;

    private static DatasetEntity dataset(long id) {
        DatasetEntity ds = new DatasetEntity();
        ds.setId(id);
        return ds;
    }

    private static INode folder(String path, NodeEntity dataSet) {
        INode folder = new INode();
        folder.setNodeType(INode.INodeType.FOLDER);
        // setPath derives pathHash itself, from the NORMALISED path. Setting it here as well —
        // with the raw one — reproduced the derivation instead of exercising it, and the stub below
        // looked it up the same way, so fixture and lookup agreed with each other while production
        // hashed something else for any path needing normalisation.
        folder.setPath(path);
        folder.setDataSet(dataSet);
        return folder;
    }

    /**
     * User Foo (write access to datasets 55 and 66) uploads strategy.txt to
     * {@code /org-a/team-b/plan/strategy.txt} with dataset 66. The folders {@code /org-a}
     * (no dataset) and {@code /org-a/team-b} (dataset 55) already exist. Expected outcome:
     *
     * <ul>
     *   <li>the pre-existing folders are reused and keep their datasets (org-a → none, team-b → 55);</li>
     *   <li>the newly created folder {@code plan} inherits the uploaded file's dataset (66);</li>
     *   <li>(the file itself carries dataset 66 — set by the caller, asserted separately below).</li>
     * </ul>
     */
    @Test
    void newlyCreatedFoldersInheritUploadedFilesDataset() {
        DatasetEntity ds66 = dataset(66);

        // /org-a/team-b already exists with dataset 55 (and org-a above it has no dataset).
        INode teamB = folder("/org-a/team-b", dataset(55));
        String teamBPath = "/org-a/team-b";

        when(fileSystemService.validateFolderPath(anyString())).thenReturn(true);
        // Deepest path does not exist yet → create it; its parent /org-a/team-b already exists.
        when(iNodeRepository.findByPathHashAndNodeType(anyLong(), eq(INode.INodeType.FOLDER), eq(INode.class)))
                .thenAnswer(inv -> {
                    long pathHash = inv.getArgument(0);
                    if (pathHash == teamB.getPathHash()) {
                        return Optional.of(teamB);
                    }
                    return Optional.empty();
                });
        when(iNodeRepository.save(any(INode.class))).thenAnswer(inv -> inv.getArgument(0));

        // The file being uploaded carries dataset 66; the path's leaf folder is created here.
        INode created = directoryService.createDirectoriesFromPath("/org-a/team-b/plan", ds66);

        // The new leaf folder "plan" inherits the file's dataset (66) and hangs under team-b.
        assertEquals("plan", created.getName());
        assertEquals(INode.INodeType.FOLDER, created.getNodeType());
        assertEquals("/org-a/team-b/plan", created.getPath());
        assertSame(ds66, created.getDataSet());
        assertEquals(66L, created.getDataSet().getId());
        assertSame(teamB, created.getParent());

        // The existing folder team-b is reused untouched and keeps dataset 55.
        assertEquals(55L, teamB.getDataSet().getId());

        // Only the missing "plan" folder is created — org-a / team-b are not re-saved or modified.
        ArgumentCaptor<INode> saved = ArgumentCaptor.forClass(INode.class);
        verify(iNodeRepository, times(1)).save(saved.capture());
        assertEquals("plan", saved.getValue().getName());
        assertEquals(66L, saved.getValue().getDataSet().getId());
    }

    /**
     * When the uploaded file has no dataset (public upload to a public area), folders created for
     * its path are public too (no dataset) — consistent with "no dataset = visible to everyone".
     */
    @Test
    void foldersCreatedForPublicUploadHaveNoDataset() {
        when(fileSystemService.validateFolderPath(anyString())).thenReturn(true);
        when(iNodeRepository.findByPathHashAndNodeType(anyLong(), eq(INode.INodeType.FOLDER), eq(INode.class)))
                .thenReturn(Optional.empty());
        when(iNodeRepository.save(any(INode.class))).thenAnswer(inv -> inv.getArgument(0));

        // No dataset passed (file uploaded to root area with no dataSet field).
        INode created = directoryService.createDirectoriesFromPath("/public-area", null);

        assertEquals("public-area", created.getName());
        assertNull(created.getDataSet());
    }
}
