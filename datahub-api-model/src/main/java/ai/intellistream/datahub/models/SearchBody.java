// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.*;

/**
 * Request body of every {@code POST /x/search}, with {@code F} the filter that endpoint narrows by.
 *
 * <p>One class in place of the four that used to say the same thing: {@code ResourceSearch},
 * {@code DataSetSearch}, {@code EventSearch} and {@code SearchCmd} differed only in the type of
 * {@code filter}, and each carried its own copy of the {@code limit} default, the {@code @Max}, and
 * the guard below. Four copies is how they drifted in the first place — {@code SearchCmd} took a
 * different search type from its siblings and none of them applied their filter at all.
 *
 * <p>The wire format is unchanged: same field names, same nesting, same defaults. A body that
 * worked against the four classes works against this one.
 *
 * <h2>Why a type parameter is safe here specifically</h2>
 * Spring derives the full parameterized type from the controller method's signature
 * ({@code ResolvableType.forMethodParameter}), so Jackson deserializes {@code filter} to the
 * concrete filter class and {@code @Valid} cascades into it. Erasure never enters into it at the
 * point that matters. Lose that generic argument — read this type raw — and {@code filter} silently
 * becomes a {@code LinkedHashMap} instead of failing, which is the trap to know about.
 *
 * <p>That safety is a property of the REST path, not of the codebase. <b>Do not reuse this shape
 * for a Pulsar payload or a JPA entity.</b> Avro builds its schema by reflecting over runtime
 * classes, sees the erased variable and produces {@code Object}; that is why every message class
 * here ({@code DataWrapperBin}, {@code EventCudMessage}) names a concrete element type instead.
 * Hibernate has the same problem for the same reason: a type variable has no column.
 *
 * <p><b>Never give this class an explicit {@code @Schema(name = ...)}.</b> An explicit name
 * overrides the per-parameterization schema naming and collapses every {@code SearchBody<F>} into
 * one schema, losing the filter type. That is exactly what {@code @Schema(name = "DataWrapper")}
 * does to {@code DataWrapper<T>}, and the 31 hand-copied classes in
 * {@code api.responses.swaggerdto} are the bill for it.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class SearchBody<F> {
    // No @AllArgsConstructor: Jackson would pick it and then reject a body that omits `limit`,
    // because a primitive int cannot be null. With only the no-arg constructor and setters, absent
    // fields keep the defaults below.

    /** The free-text phrase. Required; its own constraints live on {@link SearchForm}. */
    @Valid
    private SearchForm search = new SearchForm();

    /**
     * Optional criteria narrowing the phrase's hits. Null (or omitted) narrows nothing.
     *
     * <p>Unbounded rather than {@code <F extends NodeFilter>}, because {@code EventFilter}
     * deliberately is not a {@code NodeFilter} — events are not nodes, and inheriting {@code id} and
     * {@code name} that no reader could honour is the failure that refactor existed to remove.
     */
    @Valid
    private F filter;

    @Max(1000)
    private int limit = 100;

    /**
     * Where to continue a previous page, as handed back in {@code nextCursor}. Omit it to start a
     * new walk. A search is relevance-ordered, so the cursor names a position in that order rather
     * than a row count — the same contract the filter endpoints use.
     */
    @Schema(description = "Continue from a previous page's nextCursor. Omit to start a new walk.")
    private String cursor;

    /**
     * Guard against {@code limit <= 0}, which SQL turns into "return nothing" (LIMIT 0); fall back
     * to the default. Lombok leaves this alone rather than generating its own setter.
     */
    public void setLimit(int limit) {
        this.limit = (limit <= 0) ? 100 : limit;
    }
}
