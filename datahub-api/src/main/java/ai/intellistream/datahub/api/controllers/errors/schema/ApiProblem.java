// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The published schema for an error response. Documentation only — never instantiated.
 *
 * <h2>Why this exists rather than {@code ProblemDetail.class}</h2>
 * Pointing the OpenAPI at Spring's {@code ProblemDetail} documents the wrong shape. That type
 * stores extension members in a {@code Map} behind {@code getProperties()}, so swagger-core
 * generates a schema with a nested {@code properties} object — and the wire has no such member.
 * Spring's Jackson mixin flattens extensions to the top level, and it is applied by the message
 * converter, not by the type, so schema generation never sees it. The generated schema therefore
 * advertised a {@code properties} map that never arrives and omitted {@code fields},
 * {@code duplicated}, {@code blockedBy} and {@code missing}, which always do. A client generated
 * from it would read {@code properties.fields} and find nothing.
 *
 * <p>These classes state the emitted shape directly. {@code ProblemSchemaParityTest} renders a real
 * problem from {@code Problems} through MVC and fails if what comes out is not what is declared
 * here — the same drift the hand-copied response envelopes suffered when {@code nextCursor} was
 * added to the real one and not to theirs.
 *
 * @see ai.intellistream.datahub.api.controllers.errors.Problems
 */
@Schema(name = "Problem",
        description = """
                RFC 9457 `application/problem+json`. Branch on `type` — prose changes, a URI does \
                not. Members beyond these five are extensions (§3.2) and a conforming client \
                ignores ones it does not recognise, so new ones can appear without breaking you.""")
public class ApiProblem {

    @Schema(description = """
            The problem type, and the only member worth branching on. Known values: \
            `.../errors/bad-request`, `.../errors/validation-failed`, \
            `.../errors/constraint-violation`, `.../errors/not-found`, `.../errors/duplicate`, \
            `.../errors/conflict`, `.../errors/optimistic-lock`, `.../errors/referenced`, \
            `.../errors/would-strand`, `.../errors/naming-policy`, `.../errors/internal`. \
            Treat an unrecognised value as the generic case for its status rather than an error.""",
            format = "uri",
            example = "https://intellistream.ai/errors/bad-request")
    private String type;

    @Schema(description = "Short, human-readable summary of the problem type.",
            example = "Bad Request")
    private String title;

    @Schema(description = "The HTTP status code, repeated here for a reader holding only the body.",
            example = "400")
    private Integer status;

    @Schema(description = "What went wrong on this occurrence, in prose. For a human reading a log.",
            example = "External id already exists.")
    private String detail;

    @Schema(description = "The request path this problem refers to.",
            format = "uri", example = "/timeseries/create")
    private String instance;

    public String getType() { return type; }
    public String getTitle() { return title; }
    public Integer getStatus() { return status; }
    public String getDetail() { return detail; }
    public String getInstance() { return instance; }
}
