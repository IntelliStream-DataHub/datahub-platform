// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/**
 * Deploying a flow as yourself: {@code authorize} returns the Keycloak URL to send the browser to —
 * as JSON, since a console calling with {@code fetch} and a bearer token cannot follow a redirect
 * into a sign-in page — and Keycloak sends the browser back to {@code callback}, which carries no
 * bearer token and is the one endpoint of this service open without one (see
 * {@code SecurityConfig}). Its {@code state} is what it trusts instead.
 */
@RestController
public class DeployAuthorizationController {

    private final DeployAuthorization authorization;

    public DeployAuthorizationController(DeployAuthorization authorization) {
        this.authorization = authorization;
    }

    /** What deploying asks for: a version (default the current one) and whether it is to be on. */
    public record AuthorizeRequest(Integer version, Boolean enabled) {
    }

    /**
     * 200 {@code {"authorizationUrl": …}}: send the browser there. 403 without {@code /flows/write}
     * or write access to the flow's dataset; 404 when the flow is not visible or has no such version;
     * 400 when the version no longer compiles.
     */
    @PostMapping("/flows/{externalId}/deployment/authorize")
    public Map<String, String> authorize(@PathVariable String externalId,
                                         @RequestBody(required = false) AuthorizeRequest request) {
        return Map.of("authorizationUrl", authorization.authorize(externalId,
                request == null ? null : request.version(),
                request == null ? null : request.enabled()).toString());
    }

    /** Where Keycloak sends the browser back: on to the console, or a short page when there is none. */
    @GetMapping(DeployAuthorization.CALLBACK_PATH)
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error) {
        return switch (authorization.callback(code, state, error)) {
            case DeployAuthorization.Outcome.Redirect r ->
                    ResponseEntity.status(HttpStatus.FOUND).location(r.location()).build();
            case DeployAuthorization.Outcome.Page p ->
                    ResponseEntity.status(p.deployed() ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.TEXT_HTML)
                            .body("<!doctype html><meta charset=\"utf-8\"><title>Flow deployment</title>"
                                    + "<p>" + HtmlUtils.htmlEscape(p.message()) + "</p>");
        };
    }
}
