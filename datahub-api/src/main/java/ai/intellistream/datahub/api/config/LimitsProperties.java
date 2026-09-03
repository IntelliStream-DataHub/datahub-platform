// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.config;

import ai.intellistream.datahub.models.validation.FieldLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code datahub.limits.*}: the ceilings that keep the api usable when it is reachable by the
 * public. The per-field and per-batch caps live in the wire contract ({@link FieldLimits}); what is
 * configurable here is deployment policy rather than contract.
 */
@Component
@ConfigurationProperties(prefix = "datahub.limits")
public class LimitsProperties {

    /**
     * Largest accepted request body, in bytes. Kept below Pulsar's 5 MB per-message default: an
     * event create batch is published as a single message, so the body cap is what keeps that
     * message legal.
     */
    private long maxBodyBytes = 4L * 1024 * 1024;

    /**
     * Largest accepted body for {@code POST /timeseries/data}. Higher than the general cap because a
     * full {@link FieldLimits#DATAPOINTS_PER_COLLECTION_MAX} batch of numeric points is around 5 MB
     * of JSON on its own.
     */
    private long maxBodyBytesDatapoints = 16L * 1024 * 1024;

    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public long getMaxBodyBytesDatapoints() {
        return maxBodyBytesDatapoints;
    }

    public void setMaxBodyBytesDatapoints(long maxBodyBytesDatapoints) {
        this.maxBodyBytesDatapoints = maxBodyBytesDatapoints;
    }

    private final Rate rate = new Rate();
    private final Quota quota = new Quota();
    private final Lifetime lifetime = new Lifetime();
    private final WebSocket websocket = new WebSocket();

    public WebSocket getWebsocket() {
        return websocket;
    }

    /**
     * Concurrent WebSocket connections, and subscriptions multiplexed over one of them. Sockets are
     * capped separately from requests because a socket's cost is what happens after the handshake:
     * a durable subscription holds broker resources whether or not anyone is reading it.
     */
    public static class WebSocket {

        private boolean enabled = true;

        private int maxSocketsPerTenant = 10;
        private int maxSocketsPerUser = 10;
        private int maxSubscriptionsPerSocket = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSocketsPerTenant() {
            return maxSocketsPerTenant;
        }

        public void setMaxSocketsPerTenant(int maxSocketsPerTenant) {
            this.maxSocketsPerTenant = maxSocketsPerTenant;
        }

        public int getMaxSocketsPerUser() {
            return maxSocketsPerUser;
        }

        public void setMaxSocketsPerUser(int maxSocketsPerUser) {
            this.maxSocketsPerUser = maxSocketsPerUser;
        }

        public int getMaxSubscriptionsPerSocket() {
            return maxSubscriptionsPerSocket;
        }

        public void setMaxSubscriptionsPerSocket(int maxSubscriptionsPerSocket) {
            this.maxSubscriptionsPerSocket = maxSubscriptionsPerSocket;
        }
    }

    public Rate getRate() {
        return rate;
    }

    public Quota getQuota() {
        return quota;
    }

    public Lifetime getLifetime() {
        return lifetime;
    }

    /**
     * Daily ingest allowance per tenant, reset at 00:00 UTC. Overridable per tenant; 0 or negative
     * disables one.
     */
    public static class Quota {

        private boolean enabled = true;

        private long eventsPerDay = 100_000;
        /** Resources, timeseries, datasets, labels, policies and functions share the node table. */
        private long nodesPerDay = 50_000;
        private long edgesPerDay = 100_000;
        private long datapointsPerDay = 10_000_000;
        /**
         * Bytes of write-request body. The only quota that really bounds storage growth: an entity
         * count does not, because one legitimate entity may be a few hundred KB.
         */
        private long ingestBytesPerDay = 1024L * 1024 * 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getEventsPerDay() {
            return eventsPerDay;
        }

        public void setEventsPerDay(long eventsPerDay) {
            this.eventsPerDay = eventsPerDay;
        }

        public long getNodesPerDay() {
            return nodesPerDay;
        }

        public void setNodesPerDay(long nodesPerDay) {
            this.nodesPerDay = nodesPerDay;
        }

        public long getEdgesPerDay() {
            return edgesPerDay;
        }

        public void setEdgesPerDay(long edgesPerDay) {
            this.edgesPerDay = edgesPerDay;
        }

        public long getDatapointsPerDay() {
            return datapointsPerDay;
        }

        public void setDatapointsPerDay(long datapointsPerDay) {
            this.datapointsPerDay = datapointsPerDay;
        }

        public long getIngestBytesPerDay() {
            return ingestBytesPerDay;
        }

        public void setIngestBytesPerDay(long ingestBytesPerDay) {
            this.ingestBytesPerDay = ingestBytesPerDay;
        }
    }

    /**
     * Lifetime ceilings: how large a tenant may grow, rather than how fast. These are the free
     * playground's dimensions, since a public signup lands in one; a paying tenant's
     * {@code tenant_limits} row sets them to 0.
     */
    public static class Lifetime {

        /**
         * Off unless a deployment asks for it. These numbers size a free playground, and switching
         * them on applies them to every tenant without an override, so one already holding more than
         * {@code maxResources} would start refusing writes at the next restart.
         */
        private boolean enabled = false;

        private long maxResources = 1_000;
        private long maxEventsTotal = 25_000;
        private long maxDatapointsTotal = 1_000_000_000;
        private long maxTextDatapointsTotal = 100_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxResources() {
            return maxResources;
        }

        public void setMaxResources(long maxResources) {
            this.maxResources = maxResources;
        }

        public long getMaxEventsTotal() {
            return maxEventsTotal;
        }

        public void setMaxEventsTotal(long maxEventsTotal) {
            this.maxEventsTotal = maxEventsTotal;
        }

        public long getMaxDatapointsTotal() {
            return maxDatapointsTotal;
        }

        public void setMaxDatapointsTotal(long maxDatapointsTotal) {
            this.maxDatapointsTotal = maxDatapointsTotal;
        }

        public long getMaxTextDatapointsTotal() {
            return maxTextDatapointsTotal;
        }

        public void setMaxTextDatapointsTotal(long maxTextDatapointsTotal) {
            this.maxTextDatapointsTotal = maxTextDatapointsTotal;
        }
    }

    /**
     * Requests per minute, per tenant and per user. Deployment-wide defaults; a tenant's
     * {@code tenant_limits} row overrides any of them. 0 or negative disables that budget.
     */
    public static class Rate {

        private boolean enabled = true;

        /**
         * The primary budget: a public signup gets an organization of its own, so a tenant is a
         * customer. The per-user figures are the backstop that keeps one identity inside a tenant
         * from spending the whole tenant's allowance.
         */
        private int writePerMinutePerTenant = 2_000;
        private int readPerMinutePerTenant = 6_000;
        private int writePerMinutePerUser = 600;
        private int readPerMinutePerUser = 1_200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWritePerMinutePerTenant() {
            return writePerMinutePerTenant;
        }

        public void setWritePerMinutePerTenant(int writePerMinutePerTenant) {
            this.writePerMinutePerTenant = writePerMinutePerTenant;
        }

        public int getReadPerMinutePerTenant() {
            return readPerMinutePerTenant;
        }

        public void setReadPerMinutePerTenant(int readPerMinutePerTenant) {
            this.readPerMinutePerTenant = readPerMinutePerTenant;
        }

        public int getWritePerMinutePerUser() {
            return writePerMinutePerUser;
        }

        public void setWritePerMinutePerUser(int writePerMinutePerUser) {
            this.writePerMinutePerUser = writePerMinutePerUser;
        }

        public int getReadPerMinutePerUser() {
            return readPerMinutePerUser;
        }

        public void setReadPerMinutePerUser(int readPerMinutePerUser) {
            this.readPerMinutePerUser = readPerMinutePerUser;
        }
    }
}
