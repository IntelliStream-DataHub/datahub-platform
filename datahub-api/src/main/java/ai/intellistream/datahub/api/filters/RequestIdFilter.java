// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/** Gives every request an id: in the X-Request-Id response header, the log context and every problem body. */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    public static final String MDC_KEY = "requestId";

    // A client's own id is kept when it is short and plain, so it cannot forge log lines or headers.
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = current(request);
        if (id == null) {
            String sent = request.getHeader(HEADER);
            id = sent != null && CLIENT_ID.matcher(sent).matches() ? sent : IdGenerator.getRandomUUID7AsString();
            request.setAttribute(ATTRIBUTE, id);
            response.setHeader(HEADER, id);
        }
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The id of the request being served, or null outside one. */
    public static String current(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? id : null;
    }
}
