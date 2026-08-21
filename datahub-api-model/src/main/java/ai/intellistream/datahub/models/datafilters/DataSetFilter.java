// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Filter criteria for {@code POST /datasets/filter} and the {@code filter} of
 * {@code POST /datasets/search}.
 *
 * <p>Extends {@link NodeFilter} rather than {@link DataSetScopedFilter}: a data set is the thing
 * other nodes are scoped by, so it has no {@code dataSetId} of its own.
 *
 * <p>It adds nothing else, and that is the current truth rather than an oversight. The
 * {@code writeProtected} and {@code deactivated} flags it used to carry were removed as inert; what
 * distinguishes this filter from the generic node query is now only which node type it answers for.
 * A data-set-specific criterion belongs here when one exists.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name = "Data Set Query Filter", description = "Data Set Query Filter Object")
public class DataSetFilter extends NodeFilter {
}
