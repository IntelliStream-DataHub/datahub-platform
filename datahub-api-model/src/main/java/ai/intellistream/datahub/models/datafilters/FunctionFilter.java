// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Filter criteria for {@code POST /functions/filter} and the {@code filter} of
 * {@code POST /functions/search}.
 *
 * <p>Extends {@link DataSetScopedFilter}: a function lives in a data set, so it is narrowed by
 * {@code dataSetId} the way resources and timeseries are.
 *
 * <p>It adds nothing of its own, and that is the truth rather than an oversight — a function is a
 * plain datastore node, so every criterion it can be filtered by is a shared node criterion. What
 * distinguishes this filter from the generic node query is only which node type it answers for.
 *
 * <p><b>Not {@link ResourceFilter}, deliberately.</b> That one carries {@code isRoot} and
 * {@code nodeType}, and neither is answerable here: {@code isRoot} is a column every node shares
 * but only resources and assets ever set, so a function is always {@code false} and the filter
 * would be a documented way to ask for an empty result; {@code nodeType} is fixed by the endpoint.
 * The same reasoning keeps {@code isRoot} off {@link DataSetFilter} — see the note on
 * {@code ResourceFilter.isRoot}.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name = "Function Query Filter", description = "Function Query Filter Object")
public class FunctionFilter extends DataSetScopedFilter {
}
