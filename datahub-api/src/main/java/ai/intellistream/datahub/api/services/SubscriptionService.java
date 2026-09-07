// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.messaging.events.SubscriptionNotifyPublishEvent;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.SubscriptionNotifyMessage;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionSort;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionFilter;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import ai.intellistream.datahub.subscription.SubscriptionType;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.transformers.SubscriptionTransformer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.MessageId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final TimeseriesRepository timeseriesRepository;
    private final PulsarAdmin pulsarAdmin;
    private final TopicNames topicNames;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DataSecurity dataSecurity;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               TimeseriesRepository timeseriesRepository,
                               PulsarAdmin pulsarAdmin,
                               TopicNames topicNames,
                               ApplicationEventPublisher applicationEventPublisher,
                               DataSecurity dataSecurity) {
        this.subscriptionRepository = subscriptionRepository;
        this.timeseriesRepository = timeseriesRepository;
        this.pulsarAdmin = pulsarAdmin;
        this.topicNames = topicNames;
        this.applicationEventPublisher = applicationEventPublisher;
        this.dataSecurity = dataSecurity;
    }

    /**
     * Create one or more subscriptions. For each subscription this:
     * <ol>
     *     <li>resolves the target timeseries by id or external id,</li>
     *     <li>persists a {@link SubscriptionEntity} row to PostgreSQL, and</li>
     *     <li>creates a Pulsar subscription (named by externalId) on the shared fanout topic,
     *         with a {@code filter.key=externalId} subscription property that the broker-side
     *         entry filter uses to drop messages whose key does not match.</li>
     * </ol>
     * Runs in a single transaction so a Pulsar failure rolls the persisted row back.
     *
     * @param apiReqData wrapper containing the subscriptions to create
     * @return wrapper with the created subscriptions, including server-assigned ids and timestamps
     * @throws BadRequestException if the target timeseries is missing or the external id is already taken
     */
    @Transactional
    public DataWrapper<Subscription> create(DataWrapper<Subscription> apiReqData) {
        int requested = apiReqData.getItems() == null ? 0 : apiReqData.getItems().size();
        log.info("Creating {} subscription(s) for tenant {}", requested, TenantContext.getTenantId());
        if (requested == 0) {
            log.warn("Subscription create called with no items — payload may have failed to deserialize.");
        }

        var results = new DataWrapper<Subscription>();
        for (Subscription sub : apiReqData.getItems()) {
            int tsRefCount = sub.getTimeseries() == null ? 0 : sub.getTimeseries().size();
            log.info("Processing subscription externalId={} timeseriesRefs={}", sub.getExternalId(), tsRefCount);

            if (tsRefCount == 0) {
                throw badRequest("At least one timeseries must be specified.",
                        Map.of("externalId", String.valueOf(sub.getExternalId())));
            }

            // Resolve all referenced timeseries in one query. Fails with 400 if any are missing.
            Set<TimeseriesEntity> resolvedTimeseries = resolveTimeseries(sub);

            // Dataset ACL: the caller may only subscribe to timeseries whose dataset they can read.
            // A subscription streams every bound timeseries, so binding one the caller can't read
            // would let them stream it — enforce read access on each before persisting (throws 403).
            for (TimeseriesEntity ts : resolvedTimeseries) {
                dataSecurity.assertCanRead(ts);
            }

            // Build the JPA entity. dateCreated/lastUpdated are assigned by Hibernate on save.
            SubscriptionEntity entity = SubscriptionTransformer.toEntity(sub, resolvedTimeseries);

            SubscriptionEntity saved = persistAndProvision(entity);

            results.getItems().add(SubscriptionTransformer.toSubscription(saved));
        }
        log.info("Created {} subscription(s).", results.getItems().size());
        return results;
    }

    /**
     * Common save-and-Pulsar-provision flow used by {@link #create}. Rejects duplicates up
     * front so Pulsar provisioning can't succeed against a row that won't ultimately be
     * persisted; performs the Pulsar work inside the surrounding transaction so a Pulsar
     * failure rolls the database row back.
     */
    private SubscriptionEntity persistAndProvision(SubscriptionEntity entity) {
        if (subscriptionRepository.existsByExternalIdHash(entity.getExternalIdHash())) {
            throw badRequest("Subscription with this external id already exists.",
                    Map.of("externalId", entity.getExternalId()));
        }
        SubscriptionEntity saved = subscriptionRepository.save(entity);
        createPulsarSubscription(saved.getExternalId());
        publishNotifyMessage(EventAction.CREATE, saved);
        log.info("Created subscription id={} externalId={} bound to {} timeseries",
                saved.getId(), saved.getExternalId(), saved.getTimeseries().size());
        return saved;
    }

    /**
     * Subscriptions for the current tenant matching every supplied criterion, in the requested
     * order, one keyset page at a time.
     *
     * <p>The same shape as {@code DataSetService.filter} and the two beside it: resolve the sort
     * against a whitelist, validate the cursor against that sort, run the query, hand back the
     * cursor for the next page. It replaced a hand-rolled version that clamped the limit itself
     * with different numbers than the rest of the API used, passed the caller's sort property
     * straight into {@code Sort.by} — where an unknown one became a 500 — and had no cursor at all,
     * so a tenant past the page size had no way to reach the rest of its subscriptions.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Subscription> filter(SubscriptionRetriever retriever) {
        SubscriptionRetriever request = retriever != null ? retriever : new SubscriptionRetriever();
        SubscriptionFilter filter = request.getFilter();

        SubscriptionSort sort = SubscriptionSort.resolve(request.getSort());
        PageCursor cursor = FilterPaging.validated(request.getCursor(), sort);
        int limit = request.getLimit();

        List<SubscriptionEntity> entities = subscriptionRepository.filter(filter, limit, sort, cursor);
        log.info("Filtered {} subscription(s) for tenant {} (limit={}, sort={} {}, paged={}).",
                entities.size(), TenantContext.getTenantId(), limit, sort.property(),
                sort.descending() ? "desc" : "asc", cursor != null);

        var results = new DataWrapper<Subscription>();
        results.setItems(SubscriptionTransformer.toSubscription(entities));
        results.setNextCursor(FilterPaging.nextCursor(entities, limit, sort));
        return results;
    }

    /**
     * Delete one or more subscriptions. For each entry this:
     * <ol>
     *     <li>resolves the {@link SubscriptionEntity} by id or external id,</li>
     *     <li>deletes the row from PostgreSQL,</li>
     *     <li>deletes the Pulsar subscription from the shared fanout topic (the topic itself
     *         is never deleted — it is shared across all logical subscriptions), and</li>
     *     <li>publishes a {@link SubscriptionNotifyMessage} with {@link EventAction#DELETE} so the
     *         consumer evicts it from its in-memory cache.</li>
     * </ol>
     * Missing entries are silently skipped; the operation is effectively idempotent.
     */
    @Transactional
    public void delete(DataWrapper<IdCollection> apiReqData) {
        int requested = apiReqData.getItems() == null ? 0 : apiReqData.getItems().size();
        log.info("Deleting {} subscription(s) for tenant {}", requested, TenantContext.getTenantId());
        if (requested == 0) return;

        // Collect ids and external-id hashes into two sets, then resolve everything in a single query.
        Set<Long> ids = apiReqData.getItems().stream()
                .map(IdCollection::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> externalIdHashes = apiReqData.getItems().stream()
                .map(IdCollection::getExternalId)
                .filter(Objects::nonNull)
                .map(ExternalIds::hash)
                .collect(Collectors.toSet());

        Set<SubscriptionEntity> entities =
                subscriptionRepository.findAllByIdInOrExternalIdHashIn(ids, externalIdHashes);

        if (entities.size() < requested) {
            log.warn("Requested {} deletes but resolved {} subscription(s). Missing entries are skipped.",
                    requested, entities.size());
        }

        // Dataset ACL, the mirror of the check create() applies: a subscription streams every
        // timeseries bound to it, so the right to remove one is the right to read what it streams.
        // Without this any authenticated caller could delete anyone's subscription — create was
        // gated from the start, delete never was.
        for (SubscriptionEntity entity : entities) {
            if (entity.getTimeseries() != null) {
                entity.getTimeseries().forEach(dataSecurity::assertCanRead);
            }
        }

        // Pre-flight: reject the whole batch if any Pulsar subscription still has clients
        // connected, so we don't partially mutate Pulsar/Postgres state before failing.
        for (SubscriptionEntity entity : entities) {
            assertNoConnectedConsumers(entity.getExternalId());
        }

        for (SubscriptionEntity entity : entities) {
            String externalId = entity.getExternalId();

            // User-managed: assertNoConnectedConsumers above already proved nobody is
            // attached, so a 412 here would be a real race we want to surface.
            deletePulsarSubscription(externalId, false);

            publishNotifyMessage(EventAction.DELETE, entity);

            log.info("Deleted subscription externalId={} (was bound to {} timeseries)",
                    externalId, entity.getTimeseries() == null ? 0 : entity.getTimeseries().size());
        }

        // Batch delete in Postgres after per-entity Pulsar cleanup succeeded.
        subscriptionRepository.deleteAll(entities);
    }

    /**
     * Reject the delete if any client is still connected to the Pulsar subscription. The
     * websocket listener registers a Pulsar consumer per live client, so a non-empty
     * consumer list means an active subscriber would be silently cut off by the delete.
     */
    private void assertNoConnectedConsumers(String externalId) {
        String topicPath = topicNames.getSubscriptionFanoutTopicName(TenantContext.getTenantId());
        try {
            List<String> existingSubscriptions = pulsarAdmin.topics().getSubscriptions(topicPath);
            if (!existingSubscriptions.contains(externalId)) return;

            var subStats = pulsarAdmin.topics().getStats(topicPath).getSubscriptions().get(externalId);
            int connected = subStats == null || subStats.getConsumers() == null
                    ? 0 : subStats.getConsumers().size();
            if (connected > 0) {
                throw badRequest(
                        "Cannot delete subscription while clients are connected.",
                        Map.of("externalId", externalId,
                                "connectedConsumers", String.valueOf(connected)));
            }
        } catch (PulsarAdminException.NotFoundException e) {
            // Subscription already gone on the Pulsar side — nothing to check.
        } catch (PulsarAdminException e) {
            throw new RuntimeException("Failed to check connected consumers for " + externalId, e);
        }
    }

    /**
     * Remove the Pulsar subscription from the shared fanout topic. The topic itself is
     * long-lived and shared across every subscription, so it is never deleted here.
     *
     * <p>{@code force=true} disconnects any active consumers before deletion, which is the
     * right behavior when the binding's lifecycle has already authoritatively decided the
     * subscription must go (function deleted, edge removed, …) — any worker still holding
     * the WebSocket open is operating on a no-longer-existing binding. {@code force=false}
     * keeps the broker's HTTP 412 safety net for the user-managed path, which has its own
     * pre-flight ({@link #assertNoConnectedConsumers}) — a 412 there would surface a real
     * race that should not silently override.
     */
    private void deletePulsarSubscription(String externalId, boolean force) {
        String topicPath = topicNames.getSubscriptionFanoutTopicName(TenantContext.getTenantId());
        try {
            List<String> existingSubscriptions = pulsarAdmin.topics().getSubscriptions(topicPath);
            if (existingSubscriptions.contains(externalId)) {
                pulsarAdmin.topics().deleteSubscription(topicPath, externalId, force);
                log.debug("Deleted Pulsar subscription {} on {} (force={})", externalId, topicPath, force);
            }
        } catch (PulsarAdminException.NotFoundException e) {
            // Subscription already gone — idempotent.
        } catch (PulsarAdminException e) {
            throw new RuntimeException("Failed to delete Pulsar subscription for " + externalId, e);
        }
    }

    /**
     * Resolve every timeseries referenced by {@code sub.getTimeseries()} in one repository call.
     * Fails with 400 if any reference can't be matched. Duplicates (same timeseries referenced
     * twice) are silently collapsed because the return type is a {@link Set}.
     */
    private Set<TimeseriesEntity> resolveTimeseries(Subscription sub) {
        Set<Long> ids = sub.getTimeseries().stream()
                .map(IdCollection::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> externalIds = sub.getTimeseries().stream()
                .map(IdCollection::getExternalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<TimeseriesEntity> found = timeseriesRepository.findAllByIdOrExternalId(ids, externalIds);
        Set<TimeseriesEntity> unique = new LinkedHashSet<>(found);

        // Verify every request reference resolved to a real entity.
        Set<Long> foundIds = unique.stream().map(TimeseriesEntity::getId).collect(Collectors.toSet());
        Set<String> foundExternalIds = unique.stream().map(TimeseriesEntity::getExternalId).collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        for (IdCollection ref : sub.getTimeseries()) {
            boolean matched = (ref.getId() != null && foundIds.contains(ref.getId()))
                    || (ref.getExternalId() != null && foundExternalIds.contains(ref.getExternalId()));
            if (!matched) {
                missing.add(ref.getId() != null ? ("id=" + ref.getId()) : ("externalId=" + ref.getExternalId()));
            }
        }
        if (!missing.isEmpty()) {
            throw badRequest("One or more timeseries were not found.",
                    Map.of("missing", String.join(",", missing)));
        }
        return unique;
    }

    /**
     * Queue a {@link SubscriptionNotifyMessage} describing every timeseries the subscription
     * is (or was) bound to. The actual Pulsar publish happens after the enclosing transaction
     * commits, so consumers only ever see notifies for subscriptions that persisted.
     */
    private void publishNotifyMessage(EventAction action, SubscriptionEntity entity) {
        publishNotifyMessage(action, entity,
                entity.getTimeseries() == null ? List.of() : entity.getTimeseries());
    }

    /**
     * Variant that publishes notify carrying only a subset of the subscription's timeseries,
     * so a single add or remove updates exactly one cache entry on the consumer side instead
     * of replaying the whole routing table for that sub.
     */
    private void publishNotifyMessage(
            EventAction action, SubscriptionEntity entity, Collection<TimeseriesEntity> affected) {
        List<Long> tsIds = new ArrayList<>();
        List<String> tsExternalIds = new ArrayList<>();
        if (affected != null) {
            for (TimeseriesEntity ts : affected) {
                tsIds.add(ts.getId());
                tsExternalIds.add(ts.getExternalId());
            }
        }
        var msg = new SubscriptionNotifyMessage(
                action,
                TenantContext.getTenantId(),
                entity.getExternalId(),
                tsIds,
                tsExternalIds
        );
        applicationEventPublisher.publishEvent(new SubscriptionNotifyPublishEvent(msg));
    }

    /**
     * Create a Pulsar subscription on the shared fanout topic for this subscription's
     * externalId, then attach a {@code filter.key} subscription property that the broker-side
     * {@code SubscriptionKeyEntryFilter} reads per-dispatch to drop messages whose key does
     * not match. This is how one physical topic serves many logical subscribers.
     * <p>
     * Idempotent on Pulsar's side: if the broker reports the subscription already exists
     * (HTTP 409, typically a stray from a previous run that didn't clean up cleanly), we
     * adopt it and re-apply the {@code filter.key} property so its routing matches what
     * this Postgres row expects. Mirrors the {@link PulsarAdminException.NotFoundException}
     * handling in {@link #deletePulsarSubscription(String)}.
     */
    private void createPulsarSubscription(String externalId) {
        String topicPath = topicNames.getSubscriptionFanoutTopicName(TenantContext.getTenantId());
        try {
            pulsarAdmin.topics().createSubscription(topicPath, externalId, MessageId.latest);
            log.debug("Created Pulsar subscription {} on shared topic {}", externalId, topicPath);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            log.info("Pulsar subscription {} already exists on {} — adopting and re-applying filter.key.",
                    externalId, topicPath);
        } catch (PulsarAdminException e) {
            throw new RuntimeException("Failed to create Pulsar subscription for " + externalId, e);
        }
        try {
            pulsarAdmin.topics().updateSubscriptionProperties(
                    topicPath, externalId,
                    Map.of(TopicNames.SUBSCRIPTION_FILTER_KEY_PROP, externalId));
        } catch (PulsarAdminException e) {
            throw new RuntimeException("Failed to set filter.key on Pulsar subscription " + externalId, e);
        }
    }

    private BadRequestException badRequest(String message, Map<String, String> fields) {
        var err = new BadRequestError();
        err.setMessage(message);
        err.getFields().add(fields);
        var resp = new ResponseError<BadRequestError>();
        resp.setError(err);
        return new BadRequestException(resp);
    }
}
