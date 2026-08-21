// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.subscription.Subscription;

import java.util.*;
import java.util.stream.Collectors;

public class SubscriptionTransformer {

    public static Collection<Subscription> toSubscription(Collection<SubscriptionEntity> entities) {
        return entities.stream().map(SubscriptionTransformer::toSubscription).collect(Collectors.toList());
    }

    public static Subscription toSubscription(SubscriptionEntity entity) {
        var s = new Subscription();
        s.setId(entity.getId());
        s.setExternalId(entity.getExternalId());
        s.setName(entity.getName());
        s.setSystemManaged(entity.isSystemManaged());
        s.setDateCreated(entity.getDateCreated());
        s.setLastUpdated(entity.getLastUpdated());

        List<IdCollection> timeseries = new ArrayList<>();
        if (entity.getTimeseries() != null) {
            for (TimeseriesEntity ts : entity.getTimeseries()) {
                var ref = new IdCollection();
                ref.setId(ts.getId());
                ref.setExternalId(ts.getExternalId());
                timeseries.add(ref);
            }
        }
        s.setTimeseries(timeseries);
        return s;
    }

    public static SubscriptionEntity toEntity(Subscription sub, Set<TimeseriesEntity> timeseries) {
        var entity = new SubscriptionEntity();
        entity.setExternalId(sub.getExternalId());
        entity.setName(sub.getName());
        entity.setTimeseries(timeseries != null ? new LinkedHashSet<>(timeseries) : new LinkedHashSet<>());
        return entity;
    }
}
