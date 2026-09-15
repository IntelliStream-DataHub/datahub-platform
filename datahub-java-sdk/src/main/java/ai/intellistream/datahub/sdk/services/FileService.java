// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.files.FileUpdate;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Files — list directories, download content, and upload files. Mirrors the {@code /files} endpoints. */
public final class FileService {

    private final ApiHttp http;
    private final JavaType indexNodes; // DataWrapper<IndexNode>

    public FileService(ApiHttp http) {
        this.http = http;
        this.indexNodes = http.typeFactory().constructParametricType(DataWrapper.class, IndexNode.class);
    }

    /** GET /files/list — list the root directory. */
    public DataWrapper<IndexNode> list() {
        return http.get("/files/list", indexNodes);
    }

    /** GET /files/list{path} — list a directory by path (e.g. {@code "/reports/2026"}). */
    public DataWrapper<IndexNode> list(String path) {
        return http.get("/files/list" + path, indexNodes);
    }

    /** GET /files/download/{id} — download a file's content. */
    public byte[] download(String id) {
        return http.getBytes("/files/download/" + id);
    }

    /**
     * PUT /files — upload a file. The content is the raw request body; all metadata travels in
     * {@code X-Datahub-*} headers, which the server validates before it reads the body.
     */
    public DataWrapper<IndexNode> upload(FileUploadRequest request) {
        return http.put("/files", request.content(), headers(request), indexNodes);
    }

    /** GET /files?id= — one file or folder by its numeric id; {@code 404} when there is none. */
    public DataWrapper<IndexNode> getById(long id) {
        return http.get("/files?id=" + id, indexNodes);
    }

    /** GET /files?externalId= — one file or folder by its external id. */
    public DataWrapper<IndexNode> getByExternalId(String externalId) {
        return http.get("/files?externalId=" + URLEncoder.encode(externalId, StandardCharsets.UTF_8),
                indexNodes);
    }

    /**
     * GET /files/search — full-text search over file names, paths and metadata.
     *
     * <p>Pass {@code null} for {@code limit} to take the server default. Unlike
     * {@link #list(String)} this crosses folders, so it is the way to find a file whose location
     * you do not know.
     */
    public DataWrapper<IndexNode> search(String q, Integer limit) {
        StringBuilder url = new StringBuilder("/files/search?q=")
                .append(URLEncoder.encode(q, StandardCharsets.UTF_8));
        if (limit != null) {
            url.append("&limit=").append(limit);
        }
        return http.get(url.toString(), indexNodes);
    }

    /**
     * GET /files/trash — the soft-deleted files the caller can read.
     *
     * <p>Their {@code name} and {@code path} are the pre-deletion values; the deletion time is
     * encoded in the externalId as {@code DELETED_..._<epochMillis>}. Put one back with
     * {@link #restore(List)}.
     */
    public DataWrapper<IndexNode> trash() {
        return http.get("/files/trash", indexNodes);
    }

    /**
     * POST /files/restore — move soft-deleted files back to the path they were deleted from.
     *
     * <p>A {@code 409} when something else already occupies that path: restore is not a
     * force-overwrite, so move or rename the occupant first.
     */
    public DataWrapper<IndexNode> restore(List<IdCollection> ids) {
        return http.post("/files/restore", new DataWrapper<IdCollection>().setItems(ids), indexNodes);
    }

    /**
     * POST /files/update — rename, move, or edit the metadata of one file or folder.
     *
     * <p>Identify it by {@code externalId} or {@code id}; every other field is optional and null
     * means "leave unchanged". Setting {@code path} moves it (the target folder is created if
     * missing), and {@code metadata} and {@code relatedResources} replace rather than merge.
     *
     * <p>Takes a bare {@link FileUpdate}, not a wrapper: this endpoint updates one node per call.
     */
    public DataWrapper<IndexNode> update(FileUpdate request) {
        return http.post("/files/update", request, indexNodes);
    }

    /** POST /files/delete — delete files by id. The endpoint answers {@code 204} with no body. */
    public void delete(List<IdCollection> ids) {
        http.send("POST", "/files/delete", new DataWrapper<IdCollection>().setItems(ids));
    }

    private static Map<String, String> headers(FileUploadRequest r) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Datahub-Path", encode(r.path()));
        if (r.externalId() != null) {
            headers.put("X-Datahub-External-Id", encode(r.externalId()));
        }
        if (r.dataSetId() != null) {
            headers.put("X-Datahub-Dataset-Id", r.dataSetId().toString());
        }
        if (r.description() != null) {
            headers.put("X-Datahub-Description", encode(r.description()));
        }
        if (r.source() != null) {
            headers.put("X-Datahub-Source", encode(r.source()));
        }
        headers.put("Content-Type", r.contentType());
        return headers;
    }

    /** Percent-encode a header value (the server URL-decodes the {@code X-Datahub-*} metadata). */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
