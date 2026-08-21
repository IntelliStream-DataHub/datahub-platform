// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.services.StatsService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Internal per-tenant statistics for the home dashboard (total datapoints, events, alarms, assets,
 * ..., plus recent top event types). Served from a per-metric Valkey cache (see {@link StatsService}).
 * Callers may request a subset with {@code ?keys=events,alarms} so fast-changing metrics can be polled
 * without dragging the slow structural counts along; no {@code keys} returns them all. Hidden from
 * Swagger — a console-only convenience endpoint, not part of the public API.
 */
@Hidden
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
@Slf4j
public class StatsController {

    private final StatsService statsService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stats(@RequestParam(name = "keys", required = false) String keys) {
        try {
            List<String> requested = (keys == null || keys.isBlank())
                    ? null
                    : Arrays.stream(keys.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            return ResponseEntity.ok(statsService.getStats(requested));
        } catch (RuntimeException e) {
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
