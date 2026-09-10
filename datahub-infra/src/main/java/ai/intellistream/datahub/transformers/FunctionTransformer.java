// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
        dto.setLabels(labelsOf(entity));
        return dto;
    }

    /**
     * From the denormalised {@code labels} column, not the {@code labelEntities} M2M.
     *
     * <p>The M2M is LAZY, so reading it costs a query per row inside a session and throws
     * {@code LazyInitializationException} outside one — which is exactly what a DTO serialized
     * after the transaction closes does. Every other read path uses the denormalised string; this
     * one used to be the exception.
     */
    private static List<String> labelsOf(FunctionEntity entity) {
        String labels = entity.getLabels();
        if (labels == null || labels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(labels.split(",")).collect(Collectors.toList());
    }
}
