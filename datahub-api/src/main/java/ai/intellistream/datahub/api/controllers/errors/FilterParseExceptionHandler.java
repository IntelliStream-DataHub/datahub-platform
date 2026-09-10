// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.filter.FilterParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Renders a rejected {@code advancedFilter} expression as a 400 that repairs itself.
 *
 * <p>The extension members are the point. A message alone makes the caller re-derive the edit from
 * a description of it; {@code offset} and {@code length} let a UI underline exactly the token at
 * fault, and {@code suggestedQuery} is the whole expression with the fix already applied, ready to
 * apply in one click or paste into a script.
 *
 * <p>{@code suggestedQuery} is absent whenever the repair is ambiguous — several names were
 * equally close — because applying the wrong correction automatically is worse than offering none.
 * A client should treat its presence as "there is one obvious fix" rather than as "there is a fix".
 *
 * <p>{@code code} and {@code args} carry the same message as a stable key and its values, so a UI
 * can render it in the reader's language. {@code detail} stays the English sentence rather than
 * being replaced by the key: an SDK caller, a curl session and a log line all want something
 * readable, and a client that does not translate must not be handed {@code filter.error.syntax}.
 * A client that does translate looks up {@code code} and falls back to {@code detail} when it has
 * no entry, which is also what happens for a code added after that client shipped.
 *
 * <p>Rejection is total: no query is issued. An expression this service cannot read is never
 * partially applied or quietly dropped, which would return everything the caller may see while
 * silently ignoring what they asked for — a wrong answer wearing a 200.
 */
@RestControllerAdvice
@Slf4j
public class FilterParseExceptionHandler {

    public static final String PROBLEM_TYPE = "https://intellistream.ai/errors/filter-expression";

    @ExceptionHandler(FilterParseException.class)
    public ProblemDetail handleFilterParse(FilterParseException ex) {
        // Logged so a rejected expression is visible for detection work; at debug because a
        // malformed filter is a caller's mistake, not an incident.
        log.debug("Rejecting filter expression at offset {}: {}", ex.getOffset(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid filter expression");
        problem.setType(URI.create(PROBLEM_TYPE));
        problem.setProperty("offset", ex.getOffset());
        problem.setProperty("length", ex.getLength());
        if (ex.getCode() != null) {
            problem.setProperty("code", ex.getCode());
            problem.setProperty("args", ex.getArgs());
        }
        if (ex.getHelpCode() != null) {
            problem.setProperty("helpCode", ex.getHelpCode());
            problem.setProperty("helpArgs", ex.getHelpArgs());
        }
        if (ex.getSuggestion() != null) {
            problem.setProperty("suggestion", ex.getSuggestion());
        }
        if (ex.getSuggestedQuery() != null) {
            problem.setProperty("suggestedQuery", ex.getSuggestedQuery());
        }
        if (ex.getHelp() != null) {
            problem.setProperty("help", ex.getHelp());
        }
        return problem;
    }
}
