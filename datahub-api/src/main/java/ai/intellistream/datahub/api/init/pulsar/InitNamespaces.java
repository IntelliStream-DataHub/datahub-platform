// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.init.pulsar;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.pulsar.common.policies.data.BacklogQuota.RetentionPolicy.producer_exception;

@Component
@Slf4j
@Profile("dev")
public class InitNamespaces {

    @Value("${pulsar.internal-tenant:''}")
    private String internalTenant;

    private final PulsarAdmin admin;

    InitNamespaces(@Qualifier("pulsarAdmin") PulsarAdmin admin){
        this.admin = admin;
    }

    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        try {
            createNamespaces();
        } catch (PulsarAdminException e){
            log.error(e.getMessage(), e);
        }
    }

    public void createNamespaces() throws PulsarAdminException {
        log.info("Create Apache Pulsar Namespaces...");
        final long limitSizeGB = 2;
        final int limitTimeDays = 7;
        final int retentionTime = 7 * 60 * 24; // 7 days
        final int retentionSize = 12000; // 12 gigabytes

        // Internal tenant hosts CUD events and the subscription notify topic. The per-customer
        // subscription fan-out namespaces/topics (formerly the global public-tenant/subscriptions)
        // are provisioned separately by SubscriptionTopicProvisioner, which runs in all profiles.
        List<String> wanted = new ArrayList<>();
        wanted.add(internalTenant + "/events");
        wanted.add(internalTenant + "/subscriptions");

        Set<String> existing = new HashSet<>(admin.namespaces().getNamespaces(internalTenant));
        log.info("Found existing namespaces: {}", existing);

        List<String> namespaces = wanted.stream()
                .filter(ns -> !existing.contains(ns))
                .toList();

        BacklogQuota backlogQuota = BacklogQuota.builder()
                .limitSize(limitSizeGB * 1024 * 1024 * 1024)
                .limitTime(limitTimeDays * 60 * 24)
                .retentionPolicy(producer_exception)
                .build();

        Set<AuthAction> authActions = Set.of(AuthAction.produce, AuthAction.consume);

        namespaces.forEach(ns -> {
            try {
                admin.namespaces().createNamespace(ns);
                admin.namespaces().setBacklogQuota(ns, backlogQuota);
                admin.namespaces().setRetention(ns, new RetentionPolicies(retentionTime, retentionSize));
                admin.namespaces().grantPermissionOnNamespace(ns, "admin,istream", authActions);
                log.info("Created namespace: " + ns);
            } catch (PulsarAdminException e) {
                throw new RuntimeException(e);
            }
        });

    }
}
