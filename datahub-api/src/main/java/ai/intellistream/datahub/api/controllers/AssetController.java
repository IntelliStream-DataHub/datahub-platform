// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.AssetDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.services.AssetService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The typed endpoint family for assets: the node type that can be a navigation root and the only
 * one carrying a geographic location. Every other node type already had one of these; this closes
 * the gap.
 *
 * <p>Nothing here decides anything the generic {@code /resources} endpoints do not — each call is
 * the shared pipeline with the {@code ASSET} discriminator pinned, so the two paths cannot drift
 * apart on ACLs, naming policy, events or status codes.
 */
@RestController
@RequestMapping("/assets")
@Slf4j
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Create asset",
            description = """
                    Create one or more assets. An asset is a resource that can be a navigation root
                    and can carry a `geoLocation`; each needs a unique `externalId` and a `name`.
                    """
    )
    @ApiResponse(responseCode = "201", description = "Asset(s) created.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AssetDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Bad request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(
            path = "/create",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> createAsset(
            @Schema(implementation = AssetDataWrapper.class)
            @RequestBody DataWrapper<Asset> apiReqData) {
        try {
            return new ResponseEntity<>(assetService.create(apiReqData), HttpStatus.CREATED);
        } catch (ConstraintViolationException cve) {
            log.warn("Asset create validation failed: {}", cve.getMessage());
            return new ResponseEntity<>(
                    BuildErrorResponse.createConstraintViolationError(cve), HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException dve) {
            return new ResponseEntity<>(
                    BuildErrorResponse.createDataIntegrityViolationError(dve), HttpStatus.CONFLICT);
        } catch (DuplicateDataException e) {
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (BadRequestException e) {
            var error = e.getError();
            return new ResponseEntity<>(error, HttpStatusCode.valueOf(error.getError().getCode()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (PulsarClientException | RuntimeException e) {
            log.error("Asset create failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Fetch one asset by id",
            description = """
                    Returns the asset with this id.

                    An asset you may not read is reported as missing rather than forbidden, so a
                    404 does not tell you whether the id exists. A node of some other type is not
                    an asset, and is reported the same way.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The asset.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AssetDataWrapper.class)
            ))
    @ApiResponse(responseCode = "404", description = "No such asset, or not readable by this caller.")
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getAsset(@PathVariable("id") Long id) {
        return new ResponseEntity<>(assetService.get(id), HttpStatus.OK);
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Fetch assets by id or externalId",
            description = """
                    Look up several assets at once. Each entry needs either a numeric `id`, an
                    `externalId`, or both.

                    Ids that do not exist, are not assets, or are not readable by this caller are
                    left out of the response rather than failing the call.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The assets that were found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AssetDataWrapper.class)
            ))
    @PostMapping(path = "/byids", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> findByIdList(
            @RequestBody @Schema(implementation = IdCollectionDataWrapper.class)
            DataWrapper<IdCollection> apiReqData) {
        Set<Long> ids = apiReqData.getItems().stream()
                .map(IdCollection::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> externalIds = apiReqData.getItems().stream()
                .map(IdCollection::getExternalId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return ResponseEntity.ok(assetService.byIds(ids, externalIds));
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Filter assets",
            description = """
                    The same filter the generic `/resources/filter` takes, restricted to assets.
                    A `nodeType` in the body is ignored: this endpoint always answers with assets.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The assets that match every supplied filter.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AssetDataWrapper.class)
            ))
    @PostMapping(path = "/filter", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> filter(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Filter criteria and optional limit.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {
                                      "limit": 100,
                                      "filter": {
                                        "name": ["pump*"],
                                        "isRoot": true,
                                        "dataSetId": [{ "id": "12" }]
                                      }
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody ResourceRetreiver apiReqData) {
        return ResponseEntity.ok(assetService.filter(apiReqData));
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Search assets",
            description = """
                    Free-text search across assets. Same body as `/resources/search`; a `nodeType`
                    in the filter is ignored.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The assets that matched.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AssetDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "The request failed validation.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@Valid @RequestBody SearchBody<ResourceFilter> form) {
        return ResponseEntity.ok(assetService.search(form));
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Update asset",
            description = "Update one or more assets (and any relations). Only the fields named in "
                    + "each entry's `update` block are changed."
    )
    @ApiResponse(responseCode = "200", description = "Asset(s) updated.")
    @ApiResponse(responseCode = "400", description = "Bad request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(path = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateAsset(
            @RequestBody GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData) {
        try {
            GraphDataWrapper<NodeModel, EdgeProxy> results = assetService.update(apiReqData);
            return new ResponseEntity<>(results, HttpStatus.OK);
        } catch (ConstraintViolationException cve) {
            return new ResponseEntity<>(
                    BuildErrorResponse.createConstraintViolationError(cve), HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (PulsarClientException | RuntimeException e) {
            log.error("Asset update failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Assets")
    @Operation(
            summary = "Delete asset",
            description = "Delete one or more assets by id or externalId. Deleting an asset removes "
                    + "all of its relationships; the delete is rejected if it would strand a surviving node."
    )
    @ApiResponse(responseCode = "204", description = "Asset(s) deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400", description = "Bad request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @RequestMapping(
            path = "/delete",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            method = { RequestMethod.DELETE, RequestMethod.POST }
    )
    public ResponseEntity<?> deleteAsset(
            @Schema(implementation = IdCollectionDataWrapper.class)
            @RequestBody DataWrapper<IdCollection> apiReqData) {
        try {
            assetService.delete(apiReqData);
            return ResponseEntity.noContent().build();
        } catch (ResourceDeleteException e) {
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            log.warn("Asset delete bad request: {}", e.getError().getError().getMessage());
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (PulsarClientException | RuntimeException e) {
            log.error("Asset delete failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
