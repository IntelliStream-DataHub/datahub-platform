// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.DirectoryService;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FileTransformer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DirectoryService directoryService;
    private final INodeRepository iNodeRepository;
    private final FilesConfig filesConfig;
    private final NodeRepository nodeRepository;

    public FileTransformer(
            DirectoryService directoryService,
            INodeRepository iNodeRepository,
            FilesConfig filesConfig,
            NodeRepository nodeRepository){
        this.directoryService = directoryService;
        this.iNodeRepository = iNodeRepository;
        this.filesConfig = filesConfig;
        this.nodeRepository = nodeRepository;
    }

    public void setProperty(INode datahubFile, String formFieldName, String value) {
        switch (formFieldName) {
            case "externalId": datahubFile.setExternalId(value); break;
            case "name":  datahubFile.setName(value); break;
            case "description":  datahubFile.setDescription(value); break;
            case "source":  datahubFile.setSource(value); break;
            case "mimeType":  datahubFile.setDefaultMimeType(value); break;
            case "dataSet":  setDataSet(datahubFile, value); break;
            case "sourceDateCreated":  datahubFile.setSourceDateCreated(DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(value)); break;
            case "sourceLastUpdated":  datahubFile.setSourceLastUpdated(DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(value)); break;
            case "metadata":  datahubFile.setMetadata(parseJsonMap(value)); break;
            case "relatedResources":  datahubFile.setRelatedResources(parseJsonLongSet(value)); break;
        }
    }

    /** Parses a JSON object into the metadata map. */
    private static Map<String, String> parseJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid metadata; expected a JSON object", e);
        }
    }

    /** Parses a JSON array of numbers into the related-resources id set. */
    private static Set<Long> parseJsonLongSet(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Set<Long>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid relatedResources; expected a JSON array of ids", e);
        }
    }

    private void setDataSet(INode datahubFile, String formFieldValue) {
        Long dataSetId = Long.parseLong(formFieldValue);
        nodeRepository.findById(dataSetId).ifPresent(datahubFile::setDataSet);
    }

    @Transactional
    public void setDirectoryOrCreateIfMissing(INode datahubFile, String path){
        // "/" (as well as null/empty) is the root: there is no "/" folder node to find or create, so
        // treat it as root rather than trying to build a directory for it (which returned null -> NPE).
        if(path == null || path.isEmpty() || path.equals("/")) {
            datahubFile.setParent(null); // File belongs to root folder
        } else {
            var h = IdGenerator.xxHash(path);
            Optional<INode> maybeFolder = iNodeRepository
                    .findByPathHashAndNodeType(h, INode.INodeType.FOLDER, INode.class);
            if(maybeFolder.isPresent()) {
                datahubFile.setParent(maybeFolder.get());
            } else {
                // Path doesn't exist yet — create it. Created folders inherit the uploading file's
                // dataset so folder ACLs match file ACLs.
                INode folder = directoryService.createDirectoriesFromPath(path, datahubFile.getDataSet());
                datahubFile.setParent(folder);
                log.debug("Created directory: " + folder.getPath() +
                        " for file: " + datahubFile.getName());
            }
        }
    }

    /**
     * If an uploaded file contains mimeType: auto-detect, try to use Tika to find
     * the mime type.
     * @param datahubFile
     * @param filePath
     */
    public void setMimeType(INode datahubFile, Path filePath){
        Tika tika = new Tika();
        try {
            // Detect MIME type of the file
            String mimeType = tika.detect(filePath);
            datahubFile.setMimeType(mimeType);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * This approach allows you to extract metadata without the overhead of parsing the entire file
     * content, which is particularly useful for large files where you're only interested in
     * metadata.
     * @param datahubFile
     * @param absolutePath
     */
    public void extractMetadata(INode datahubFile, String absolutePath){
        Metadata metadata = new Metadata();

        try (InputStream stream = new FileInputStream(absolutePath)) {
            // Initialize the parser
            AutoDetectParser parser = new AutoDetectParser();

            // Empty handler since we don't need to extract content
            BodyContentHandler handler = new BodyContentHandler(0);

            // Create a context to control the parsing
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            // Only parse metadata
            parser.parse(stream, handler, metadata, context);

            datahubFile.setMetadata(Arrays.stream(metadata.names())
                    .filter(name -> metadata.get(name) != null)
                    .collect(Collectors.toMap(name -> name, metadata::get)));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

    }

    public List<IndexNode> transformToIndexNode(List<INode> indexNodes){
        return indexNodes.stream()
                .map(this::transform)
                .collect(Collectors.toList());
    }

    public IndexNode transform(@NotNull INode node){
        IndexNode dto = new IndexNode();
        dto.setId(node.getId());
        dto.setExternalId(node.getExternalId());
        dto.setName(node.getName());
        dto.setDescription(node.getDescription());
        dto.setDateCreated(node.getDateCreated());
        dto.setLastUpdated(node.getLastUpdated());
        dto.setSource( node.getSource() );
        dto.setMimeType( node.getMimeType() );
        dto.setSourceDateCreated( node.getSourceDateCreated() );
        dto.setSourceLastUpdated( node.getSourceLastUpdated() );

        dto.setMetadata( new HashMap<>(node.getMetadata()) );
        dto.setRelatedResources( new TreeSet<>(node.getRelatedResources()) );

        dto.setType(node.getNodeType().toString());
        if( node.getNodeType() == INode.INodeType.FILE ){
            dto.setPath( node.getPath() + "/" + node.getName() );
            dto.setSize( node.getSize() );
            dto.setChecksum( Hex.encodeHexString(node.getChecksum()) );
        } else if( node.getNodeType() == INode.INodeType.FOLDER ){
            dto.setPath( node.getPath() );
            dto.setSize(0L);
        }

        // Handle the parent directory:
        if (node.getParent() != null) {
            try {
                // Attempt to access parent's ID and name. This will trigger lazy loading if not already loaded.
                dto.setParentId(node.getParent().getId());
                dto.setParentExternalId(node.getParent().getExternalId());
            } catch (Exception e) {
                // Log the error if needed, but don't rethrow.
                // This means the parent could not be loaded (e.g., LazyInitializationException)
                // and thus parentId and parentName will remain null in the DTO.
                log.error("Could not load parent directory for File DTO conversion: " + e.getMessage());
            }
        }
        if (node.getDataSet() != null) {
            dto.setDataSetId(node.getDataSet().getId());
        }

        return dto;
    }
}
