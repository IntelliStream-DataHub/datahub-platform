// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdAndType;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.timeseries.Timeseries;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface NodeRepo {

    <T> List<T> list(int maxResults, Class<T> type);
    <T> List<T> list(int maxResults, NodeType nodeType, Class<T> type);

    List<NodeEntity> list(int maxResults);
}

