// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.Collection;

public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private static final String REQUIRED_AUTHORITY = "DATAHUB_CONSOLE";

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {

        Authentication auth = (Authentication) request.getUserPrincipal();
        String message = "Access denied.";
        if (auth != null) {
            Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
            boolean hasDatahubConsole = authorities.stream()
                    .anyMatch(a -> REQUIRED_AUTHORITY.equalsIgnoreCase(a.getAuthority()));
            if (!hasDatahubConsole) {
                message = "Access denied: missing authority '" + REQUIRED_AUTHORITY + "'.";
            }
        }

        // Basic content negotiation: JSON for API/AJAX, otherwise forward to a view.
        String accept = request.getHeader("Accept");
        boolean wantsJson = accept != null && accept.contains("application/json");
        boolean isAjax = "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));

        if (wantsJson || isAjax) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"" + message + "\"}");
            response.getWriter().flush();
            return;
        }

        response.sendError(HttpServletResponse.SC_FORBIDDEN, message);
    }
}

