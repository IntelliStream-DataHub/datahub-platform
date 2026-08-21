// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.ConflictError;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.SubscriptionDataWrapper;
import ai.intellistream.datahub.api.services.SubscriptionService;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/subscriptions")
@Slf4j
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Tag(name = "Subscriptions")
    @Operation(
            summary = "Create subscription",
            description = """
                    Create one or more **subscriptions**. A subscription is a named stream that
                    delivers live data-points from one or more timeseries to a client (e.g. a
                    dashboard) over a WebSocket connection.

                    Each subscription needs a unique `externalId` within your tenant, a `name`,
                    and a non-empty `timeseries[]` list identifying which timeseries to stream
                    — by `id`, `externalId`, or both.

                    ### Retention
                    The subscription's backlog survives across client disconnects: if a client
                    drops and reconnects, it resumes from where it left off. To reset the
                    position, delete and recreate the subscription.
                    """
    )
    @ApiResponse(responseCode = "201", description = "The subscription was created and is ready to accept WebSocket clients.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SubscriptionDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description =
            "Typical causes: `externalId` already in use, `timeseries[]` empty, or one of " +
                    "the referenced timeseries doesn't exist. The `fields` list tells you " +
                    "which input was wrong.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "One of the referenced timeseries was modified or deleted while your subscription " +
                    "was being created. Re-fetch the timeseries and retry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class)
            ))
    @PostMapping(
            path = "/create",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> createSubscription(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Subscriptions to create.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubscriptionDataWrapper.class),
                            examples = @ExampleObject(
                                    name = "Dashboard feed for two sensors",
                                    value = """
                                            {
                                              "items": [
                                                {
                                                  "externalId": "fleet_dashboard",
                                                  "name": "Fleet dashboard live feed",
                                                  "timeseries": [
                                                    { "externalId": "sensor_temp_room_a" },
                                                    { "externalId": "sensor_flow_main" }
                                                  ]
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody
            @Schema(implementation = SubscriptionDataWrapper.class)
            DataWrapper<Subscription> apiReqData
    ) {
        try {
            DataWrapper<Subscription> data = subscriptionService.create(apiReqData);
            return new ResponseEntity<>(data, HttpStatus.CREATED);
        } catch (ConstraintViolationException cve) {
            log.warn("Subscription create validation failed: {}", cve.getMessage());
            var err = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(err, HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            log.warn("Subscription create bad request: {}", e.getError().getError().getMessage());
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // RuntimeException catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (RuntimeException e) {
            log.error("Subscription create failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Subscriptions")
    @Operation(
            summary = "List subscriptions",
            description = """
                    List subscriptions in your tenant.

                    Leave the body empty (or omit `filter`) to list every subscription. Provide
                    a `filter.timeseries[]` list to return only subscriptions that include at
                    least one of the named timeseries.

                    `limit` caps the result size (default 100, max 10 000). `sort` controls the
                    order; default is `dateCreated` descending (newest first).
                    """
    )
    @ApiResponse(responseCode = "200", description = "Subscriptions matching the filter, ordered per `sort` (default newest first).",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SubscriptionDataWrapper.class)
            ))
    @PostMapping(
            path = "/list",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> listSubscriptions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = false,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                    @ExampleObject(
                                            name = "List all (default)",
                                            value = "{}"
                                    ),
                                    @ExampleObject(
                                            name = "Only subscriptions touching a specific timeseries",
                                            value = """
                                                    {
                                                      "limit": 100,
                                                      "filter": {
                                                        "timeseries": [
                                                          { "externalId": "sensor_temp_room_a" }
                                                        ]
                                                      }
                                                    }
                                                    """
                                    )
                            }
                    )
            )
            @RequestBody(required = false)
            SubscriptionRetriever retriever
    ) {
        try {
            DataWrapper<Subscription> data = subscriptionService.list(retriever);
            return ResponseEntity.ok(data);
        } catch (RuntimeException e) {
            log.error("Subscription list failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Tag(name = "Subscriptions")
    @Operation(
            summary = "Delete subscription",
            description = """
                    Delete one or more subscriptions by `id` or `externalId`. Any connected
                    WebSocket clients are disconnected and will not reconnect.

                    ### Reject if clients are connected
                    The delete is rejected with `400` if at least one client is actively
                    connected to the subscription — to avoid silently dropping live feeds.
                    Disconnect the clients (or wait for them to drop) and retry.

                    ### Idempotent
                    Entries that don't exist are silently skipped; the call returns `204` as
                    long as no active client prevents a delete.
                    """
    )
    @ApiResponse(responseCode = "204", description = "The subscriptions were deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "400", description =
            "At least one subscription still has a live client connected. The response names " +
                    "the offending `externalId` and the connected-consumer count.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class),
                    examples = @ExampleObject(value = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Cannot delete subscription while clients are connected.",
                                "fields": [
                                  { "externalId": "fleet_dashboard", "connectedConsumers": "2" }
                                ]
                              }
                            }
                            """)
            ))
    @ApiResponse(responseCode = "409", description =
            "Someone else changed or deleted one of the subscriptions while your delete was " +
                    "in flight. No subscriptions were removed.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class)
            ))
    @RequestMapping(
            path = "/delete",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            method = { RequestMethod.DELETE, RequestMethod.POST }
    )
    public ResponseEntity<?> deleteSubscription(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdCollectionDataWrapper.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "items": [
                                        { "externalId": "fleet_dashboard" }
                                      ]
                                    }
                                    """)
                    )
            )
            @RequestBody
            @Schema(implementation = IdCollectionDataWrapper.class)
            DataWrapper<IdCollection> apiReqData
    ) {
        try {
            subscriptionService.delete(apiReqData);
            return ResponseEntity.noContent().build();
        } catch (BadRequestException e) {
            log.warn("Subscription delete bad request: {}", e.getError().getError().getMessage());
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler — the broad
        // RuntimeException catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (RuntimeException e) {
            log.error("Subscription delete failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
