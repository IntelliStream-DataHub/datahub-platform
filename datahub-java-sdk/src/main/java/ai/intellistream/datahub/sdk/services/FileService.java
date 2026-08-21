// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
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

    /** POST /files/delete — delete files by id. */
    public DataWrapper<IndexNode> delete(List<IdCollection> ids) {
        return http.post("/files/delete", new DataWrapper<IdCollection>().setItems(ids), indexNodes);
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
