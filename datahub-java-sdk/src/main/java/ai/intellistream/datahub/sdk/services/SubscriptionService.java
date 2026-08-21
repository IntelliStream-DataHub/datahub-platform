// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.sdk.subscriptions.SubscriptionListener;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import tools.jackson.databind.JavaType;

import java.util.List;

/**
 * Subscriptions — durable fan-out subscriptions over time-series. Mirrors the
 * {@code /subscriptions} REST endpoints; live delivery is over WebSocket via {@link SubscriptionListener}.
 */
public final class SubscriptionService {

    private final ApiHttp http;
    private final JavaType subscriptions; // DataWrapper<Subscription>

    public SubscriptionService(ApiHttp http) {
        this.http = http;
        this.subscriptions = http.typeFactory().constructParametricType(DataWrapper.class, Subscription.class);
    }

    /** POST /subscriptions/create */
    public DataWrapper<Subscription> create(List<Subscription> subs) {
        return http.post("/subscriptions/create", new DataWrapper<Subscription>().setItems(subs), subscriptions);
    }

    /** POST /subscriptions/list */
    public DataWrapper<Subscription> list(SubscriptionRetriever retriever) {
        return http.post("/subscriptions/list", retriever, subscriptions);
    }

    /** POST /subscriptions/delete */
    public DataWrapper<Subscription> delete(List<IdCollection> ids) {
        return http.post("/subscriptions/delete", new DataWrapper<IdCollection>().setItems(ids), subscriptions);
    }

    /**
     * Open a live WebSocket listener over the named subscriptions. Stream messages to a callback with
     * {@link SubscriptionListener#stream}, or drain {@link SubscriptionListener#poll} yourself; ack the
     * processed ones and close it when done.
     */
    public SubscriptionListener listen(List<String> externalIds) {
        return SubscriptionListener.connect(http.httpClient(), http.baseUrl(), http.token(), http.mapper(), externalIds);
    }
}
