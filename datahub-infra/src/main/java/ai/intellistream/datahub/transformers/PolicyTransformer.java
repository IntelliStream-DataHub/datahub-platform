// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.PolicyType;
import ai.intellistream.datahub.models.Resource;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Converts between NodeEntity and Policy / PolicyType.
 */
public class PolicyTransformer {

    /**
     * Convert NodeEntity -> PolicyType (simple enum)
     */
    public static PolicyType toPolicyType(NodeEntity item) {
        if (item == null || item.getName() == null) {
            throw new IllegalArgumentException("Policy node must have a name.");
        }
        return PolicyType.valueOf(item.getName().toUpperCase(Locale.ROOT));
    }

    /**
     * Convert NodeEntity -> Policy (full DTO with metadata/value)
     */
    public static Policy toPolicy(NodeEntity item) {
        Policy p = new Policy();
        p.setId(item.getId());
        p.setDescription(item.getDescription());
        p.setName(item.getName());
        p.setSource(item.getSource());

        p.setExternalId(item.getExternalId());
        // Deliberately not item.getNodeType().getName(). A policy's node type is fixed —
        // PolicyEntity is @DiscriminatorValue("6") and every policy query filters node_type = 6 —
        // so that association could only ever answer "POLICY", which this line already said in its
        // null branch. Reading it cost a join or a lazy load per policy: with the session open
        // (listAllPolicies) an extra SELECT each, and with the entity detached a
        // LazyInitializationException, since open-in-view is off. Both were paid to fetch a
        // constant. Not touching it also frees every policy finder from having to fetch nodeType.
        p.setNodeType("POLICY");

        try {
            p.setType(PolicyType.valueOf(item.getName().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            p.setType(null);
        }

        p.setCreatedTime(item.getDateCreated());
        p.setLastUpdatedTime(item.getLastUpdated());

        // Copy into a plain map so the LAZY @ElementCollection is initialized inside the txn;
        // passing the PersistentMap through fails with LazyInitializationException at JSON time.
        p.setMetadata(item.getMetadata() == null ? null : new HashMap<>(item.getMetadata()));

        p.setDeactivated(item.isDeactivated());

        return p;
    }

    /**
     * Convert a collection of NodeEntity -> Collection<Policy>.
     */
    public static Collection<Policy> toPolicies(Collection<NodeEntity> results) {
        return results.stream().map(PolicyTransformer::toPolicy).toList();
    }

    /**
     * Convert Resource -> Policy (graph transformations)
     */
    public static Policy toPolicy(Resource resource) {
        Policy p = new Policy();
        p.setId(resource.getId());
        p.setName(resource.getName());
        p.setExternalId(resource.getExternalId());
        p.setDescription(resource.getDescription());
        p.setCreatedTime(resource.getCreatedTime());
        p.setLastUpdatedTime(resource.getLastUpdatedTime());

        if (resource.getMetadata() != null) {
            p.setMetadata(new HashMap<>(resource.getMetadata()));
        }

        return p;
    }
}
