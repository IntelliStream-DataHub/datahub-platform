// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers {@code fts_match(name, externalId, description, phrase)}, the Postgres full-text match
 * behind every node search, so it can be used as one predicate among others in a Criteria query.
 *
 * <h2>Why this exists</h2>
 * The search endpoints were two queries against the same table: a hand-written native SQL full-text
 * query, then a second Criteria query applying the caller's {@code filter} to the ids it returned.
 * The split was never semantic, only mechanical — native SQL and the Criteria API cannot compose,
 * so the phrase could not be expressed as a predicate alongside the rest.
 *
 * <p>That cost more than a round trip. The planner never saw the conjunction, so it could not
 * choose the cheaper index when a filter criterion was far more selective than the phrase;
 * {@code LIMIT} could not short-circuit, so a search for 50 rows hydrated up to a 10 000-row
 * candidate ceiling; and worst, the ceiling made the result <em>wrong</em> past it. With no
 * {@code ORDER BY} on the candidate query, a phrase matching more than 10 000 rows had an arbitrary
 * 10 000 of them narrowed, so a row matching both the phrase and the filter could simply not come
 * back. Registering the match as a function collapses the two queries into one and deletes the
 * ceiling along with the failure mode.
 *
 * <h2>The pattern mirrors the index, exactly</h2>
 * {@code node_fts_idx} (migration {@code V25}) is a GIN index on the expression
 * {@code to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(external_id,'') || ' ' ||
 * coalesce(description,''))}. Postgres only uses an expression index when the query expression
 * parses to the same tree, so this pattern reproduces it term for term, including the
 * {@code 'simple'} configuration and the {@code coalesce} guards. Change one and the index stops
 * being used, silently: the query still returns the right rows, by sequential scan.
 *
 * <p>The three columns are passed as arguments rather than written into the pattern so Hibernate
 * renders them qualified by the root's alias. {@link NodePredicateBuilder} joins metadata and
 * labels, and an unqualified {@code name} beside those joins is ambiguous SQL. Qualifying does not
 * affect the index match: an alias does not change how the expression parses.
 */
public class FtsMatchFunctionContributor implements FunctionContributor {

    public static final String FUNCTION_NAME = "fts_match";

    /** {@code ts_rank} of the same document against the same query, for {@code ORDER BY}. */
    public static final String RANK_FUNCTION_NAME = "fts_rank";

    /** The indexed document: {@code ?1..?3} are name, externalId and description. */
    private static final String DOCUMENT =
            "to_tsvector('simple', coalesce(?1,'') || ' ' || coalesce(?2,'') || ' ' || coalesce(?3,''))";

    /**
     * The parsed query. {@code ?4} is the caller's phrase.
     *
     * <p>The {@code nullif} is load-bearing. A phrase yielding no lexemes — punctuation only, or
     * under a stemming configuration an ordinary stopword like {@code the} — makes
     * {@code websearch_to_tsquery} return an <em>empty</em> tsquery, whose text is the empty
     * string. Appending {@code ':*'} to that builds the literal {@code ':*'}, and
     * {@code to_tsquery('simple', ':*')} is a syntax error, so the request dies as a 500 rather
     * than as an empty result.
     *
     * <p>{@code nullif} collapses the empty case to NULL, which propagates: NULL concatenated is
     * NULL, {@code to_tsquery(NULL)} is NULL, and {@code tsvector @@ NULL} is NULL, which
     * {@code WHERE} reads as no match. A phrase that means nothing therefore finds nothing, which
     * is the answer the caller was owed. {@code ts_rank} against a NULL query is likewise NULL and
     * sorts last, so the ordering needs no special case either.
     *
     * <p>This was previously masked by a character whitelist on {@code SearchForm.query} that
     * happened to guarantee a lexeme. The guard belongs here, beside the concatenation that cannot
     * survive an empty string, rather than in a wire model that has no idea why it is refusing.
     */
    private static final String QUERY =
            "to_tsquery('simple', nullif(cast(websearch_to_tsquery('simple', ?4) as text), '') || ':*')";

    /**
     * {@code ?1..?3} are the searched columns, {@code ?4} the caller's phrase.
     *
     * <p>{@code websearch_to_tsquery} parses the phrase the way a search box user expects: bare
     * words AND together, {@code or} is a disjunction, a quoted span is a phrase and a leading
     * {@code -} negates. All of it is reachable now that {@code SearchForm.query} no longer
     * restricts the character set.
     *
     * <p>Casting the result to text and appending {@code :*} makes a prefix match of the
     * <em>last</em> lexeme only, since the suffix lands on the end of the rendered query rather
     * than on each term: {@code pump stat} becomes {@code 'pump' & 'stat':*} and finds
     * "pump station", while {@code pum station} becomes {@code 'pum' & 'station':*} and finds
     * nothing. That is search-as-you-type behaviour — the word being typed is a prefix, the words
     * already finished must match whole — and it is deliberate, not a bug, but it is not general
     * fuzzy matching and should not be described as such.
     */
    private static final String PATTERN = DOCUMENT + " @@ " + QUERY;

    /**
     * The relevance score for the same document and query, used only in {@code ORDER BY}.
     *
     * <p>An {@code ORDER BY} expression does not have to match {@code node_fts_idx} the way the
     * predicate does — the index selects the rows, this only sorts the ones already selected. It is
     * still written from the same two fragments so the score can never be computed against a
     * different document or a differently parsed query than the match was.
     */
    private static final String RANK_PATTERN = "ts_rank(" + DOCUMENT + ", " + QUERY + ")";

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        functionContributions.getFunctionRegistry()
                .patternDescriptorBuilder(FUNCTION_NAME, PATTERN)
                .setExactArgumentCount(4)
                .setInvariantType(functionContributions.getTypeConfiguration()
                        .getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.BOOLEAN))
                .register();

        functionContributions.getFunctionRegistry()
                .patternDescriptorBuilder(RANK_FUNCTION_NAME, RANK_PATTERN)
                .setExactArgumentCount(4)
                .setInvariantType(functionContributions.getTypeConfiguration()
                        .getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.FLOAT))
                .register();
    }
}
