// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.subscription.SubscriptionType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
        name = "subscription",
        uniqueConstraints = @UniqueConstraint(name = "uq_subscription_external_id_hash", columnNames = "external_id_hash")
)
@Getter
@Setter
public class SubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min = 3, max = 256)
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @NotNull
    @Column(name = "external_id_hash", nullable = false)
    private Long externalIdHash;

    @NotNull
    @Size(min = 3, max = 256)
    @Column(name = "name", nullable = false)
    private String name;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "subscription_timeseries",
            joinColumns = @JoinColumn(name = "subscription_id"),
            inverseJoinColumns = @JoinColumn(name = "timeseries_id")
    )
    private Set<TimeseriesEntity> timeseries = new LinkedHashSet<>();

    /**
     * Pulsar subscription type the WS handler instantiates the consumer with.
     * Default {@link SubscriptionType#FAILOVER} preserves the human-watcher UX where every
     * connected client sees every message; {@link SubscriptionType#KEY_SHARED} would split the load
     * across worker processes with per-{@code orderingKey} sticky dispatch.
     *
     * <p>Nothing writes anything but the default. It was to be set by the function-binding
     * lifecycle that also owned the {@code system_managed} flag — the flag is gone (V43), and this
     * column stays only because the WebSocket handler reads it and a real second consumer type is
     * plausible. It is not exposed on {@code /subscriptions/create}.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_type", nullable = false, length = 32)
    private SubscriptionType subscriptionType = SubscriptionType.FAILOVER;

    @CreationTimestamp
    @Column(name = "date_created", nullable = false, updatable = false)
    private OffsetDateTime dateCreated;

    @UpdateTimestamp
    @Column(name = "last_updated", nullable = false)
    private OffsetDateTime lastUpdated;

    /**
     * Optimistic-lock counter. See {@link NodeEntity#version} for the rationale — covers the
     * concurrent create/delete subscription race, where the DB row and the Pulsar topic
     * lifecycle are managed in the same transaction.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    public void setExternalId(String externalId) {
        this.externalId = externalId;
        this.externalIdHash = ExternalIds.hash(externalId);
    }
}
