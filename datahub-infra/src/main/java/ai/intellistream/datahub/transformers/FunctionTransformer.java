// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.Label;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FunctionTransformer {

    public Collection<Function> toFunction(Collection<FunctionEntity> entities) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();
        return entities.stream().map(this::toFunction).collect(Collectors.toList());
    }

    public Function toFunction(FunctionEntity entity) {
        var f = new Function();
        f.setId(entity.getId());
        f.setExternalId(entity.getExternalId());
        f.setName(entity.getName());
        f.setDescription(entity.getDescription());
        f.setSource(entity.getSource());
        f.setMetadata(entity.getMetadata() == null ? new HashMap<>() : new HashMap<>(entity.getMetadata()));
        f.setLabels(entity.getLabelEntities() == null
                ? new ArrayList<>()
                : entity.getLabelEntities().stream().map(Label::getName).collect(Collectors.toList()));
        if (entity.getDataSet() != null) {
            f.setDataSetId(entity.getDataSet().getId());
        }
        if (entity.getDateCreated() != null) {
            f.setCreatedTime(entity.getDateCreated());
        }
        if (entity.getLastUpdated() != null) {
            f.setLastUpdatedTime(entity.getLastUpdated());
        }
        return f;
    }
}
