// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Schema(name = "Search Form", description = "Search Form Object")
public class SearchForm {

    /**
     * The phrase to search for, shared by all four {@code POST /x/search} endpoints.
     *
     * <p>There is no character whitelist. There used to be — Latin letters, spaces and digits only —
     * and it was rejecting the things people most want to search for: every externalId, since this
     * platform's convention is snake_case and {@code _} was not permitted, and every non-Latin
     * script, so a tenant naming its assets in Cyrillic or Chinese could not search at all.
     *
     * <p>It was never a safety control. The phrase is a bound parameter, so nothing in it reaches
     * the SQL as text. What it was actually doing was keeping the search query away from an input
     * it could not survive: a phrase yielding no lexemes produced an empty tsquery, and the
     * {@code || ':*'} prefix append then built the literal {@code ':*'}, which is a syntax error.
     * Permitting only letters, digits and spaces guaranteed at least one lexeme under the
     * {@code 'simple'} configuration, which has no stopwords. Two unrelated facts happening to line
     * up. {@code FtsMatchFunctionContributor} now guards the empty case directly, so the phrase can
     * be whatever the caller meant.
     *
     * <p>The bounds that remain are their own justification. Blank is a client bug worth a 400 —
     * an empty search box submitting and quietly returning the whole tenant is the failure this
     * prevents. The upper bound keeps an unbounded phrase from becoming an unbounded tsquery
     * against a GIN index. The lower bound keeps single-character prefix searches out: {@code a:*}
     * scans a large slice of the index, and since results are ranked, every match is scored before
     * {@code limit} applies, so it cannot be cut short.
     */
    @NotBlank
    @NotNull
    @Size(min = 3, max = 140)
    private String query;

}
