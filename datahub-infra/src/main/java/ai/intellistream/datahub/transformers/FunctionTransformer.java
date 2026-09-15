// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * {@code FunctionEntity} to {@link Function}. A function is a plain datastore node: it adds
 * nothing to the shared node shape, so this is the shared mapping and its labels.
 */
public final class FunctionTransformer {

    private FunctionTransformer() {
    }

    public static Collection<Function> toFunction(Collection<FunctionEntity> entities) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();
        return entities.stream().map(FunctionTransformer::from).collect(Collectors.toList());
    }

    public static Function from(FunctionEntity entity) {
        Function dto = NodeBaseFields.apply(new Function(), entity);
        dto.setLabels(NodeBaseFields.labelsOf(entity));
        return dto;
    }

}
