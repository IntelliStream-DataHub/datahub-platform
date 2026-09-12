// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm.web;

import ai.intellistream.datahub.rvm.RvmConversion;
import ai.intellistream.datahub.rvm.RvmConversionException;
import ai.intellistream.datahub.rvm.RvmConverter;
import ai.intellistream.datahub.rvm.config.RvmApiClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Converts an uploaded AVEVA model to glTF on demand.
 *
 * <p>Synchronous by design. A real E3D model converts in tens of milliseconds, so there is nothing
 * to queue, nothing stored and nothing to reconcile: the caller waits for the model it asked for.
 *
 * <p>The service holds no permissions of its own. It fetches the source with the caller's own JWT,
 * so a file outside their readable datasets is a 404 from the api and stays a 404 here.
 */
@RestController
@RequestMapping("/models")
@Slf4j
public class ConversionController {

    private final RvmApiClientFactory clients;
    private final RvmConverter converter;

    public ConversionController(RvmApiClientFactory clients, RvmConverter converter) {
        this.clients = clients;
        this.converter = converter;
    }

    /**
     * The model named by {@code rvm}, converted to a GLB.
     *
     * <p>{@code attributes} is the external id of the {@code .att} / {@code .txt} sidecar that
     * carries the PDMS tags. Pass it whenever the upload included one: without it the geometry is
     * identical but every node loses its tag, discipline and material, which is most of the reason
     * to put a plant model in here at all. The caller supplies it rather than this service hunting
     * for it, because the console already lists the folder to find a model's companions.
     */
    @GetMapping(value = "/gltf", produces = "model/gltf-binary")
    public ResponseEntity<byte[]> toGltf(
            @RequestParam("rvm") String rvm,
            @RequestParam(value = "attributes", required = false) String attributes) {

        var client = clients.forCurrentUser();
        Path work = createWorkDirectory();
        try {
            Path model = write(work, "model.rvm", download(client, rvm));
            Path sidecar = Optional.ofNullable(attributes)
                    .map(id -> write(work, "model.att", download(client, id)))
                    .orElse(null);

            RvmConversion converted = converter.convert(model, sidecar);
            log.debug("Converted {} into {} bytes of glTF", rvm, converted.glb().length);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("model/gltf-binary"))
                    .body(converted.glb());
        } catch (RvmConversionException e) {
            // The model was readable but is not something we could convert. That is the file's
            // problem, not the request's, so say so rather than reporting a server fault.
            log.warn("Could not convert {}: {}", rvm, e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not convert this model");
        } finally {
            deleteRecursively(work);
        }
    }

    private static byte[] download(ai.intellistream.datahub.sdk.client.DatahubClient client, String id) {
        try {
            return client.files().download(id);
        } catch (RuntimeException e) {
            // The api hides a file outside the caller's readable datasets rather than admitting it
            // exists, so "not found" covers "not yours" too.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file: " + id, e);
        }
    }

    /**
     * The converter reads paths, not streams, so the inputs are staged on disk. A directory per
     * request keeps concurrent conversions from colliding on a name, and it is removed either way.
     */
    private static Path createWorkDirectory() {
        try {
            return Files.createTempDirectory("rvm-");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not stage the model for conversion", e);
        }
    }

    private static Path write(Path directory, String name, byte[] bytes) {
        try {
            return Files.write(directory.resolve(name), bytes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not stage the model for conversion", e);
        }
    }

    private static void deleteRecursively(Path directory) {
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Left {} behind: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Left {} behind: {}", directory, e.getMessage());
        }
    }
}
