// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /datasets/{id}} was added alongside the pre-existing literal {@code GET /datasets/policies}.
 * A variable segment that also matches a literal route is exactly how a new endpoint silently
 * swallows an old one, so pin the precedence rather than trusting it: Spring must still route
 * {@code /datasets/policies} to the literal mapping, or the policy listing would start trying to
 * parse "policies" as a {@code Long} id and 400.
 */
class DataSetRoutePrecedenceTest {

    @Test
    void literalPoliciesRouteWinsOverTheIdPathVariable() {
        PathPatternParser parser = new PathPatternParser();
        PathPattern literal = parser.parse("/datasets/policies");
        PathPattern variable = parser.parse("/datasets/{id}");

        List<PathPattern> patterns = new ArrayList<>(List.of(variable, literal));
        patterns.sort(PathPattern.SPECIFICITY_COMPARATOR);

        assertEquals(literal.getPatternString(), patterns.get(0).getPatternString(),
                "the literal /datasets/policies must sort ahead of /datasets/{id}");
    }
}
