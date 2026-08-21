// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for the privileged <em>type-labels</em>. A node's type is intrinsic — it is
 * decided from its label names at create time ({@link ai.intellistream.datahub.services.NodeService})
 * and fixed by the entity's discriminator thereafter. These labels therefore must never be added,
 * removed, or swapped on an existing node.
 *
 * <p>{@link #ALL} is every type-label; {@link #CREATABLE} is the subset a caller may choose when
 * creating a node ({@code TIMESERIES} is created through its own API, not the resource API).
 * {@link #forEntity(NodeEntity)} returns the type-label a persisted node must carry.
 */
public final class TypeLabels {

    public static final String ASSET = "ASSET";
    public static final String DATASET = "DATASET";
    public static final String POLICY = "POLICY";
    public static final String TIMESERIES = "TIMESERIES";
    public static final String FUNCTION = "FUNCTION";

    public static final Set<String> ALL = Set.of(ASSET, DATASET, POLICY, TIMESERIES, FUNCTION);

    /** The type-labels a client may pick when creating a node via the resource API. */
    public static final Set<String> CREATABLE = Set.of(ASSET, DATASET, POLICY, FUNCTION);

    private TypeLabels() {}

    /** True if {@code name} is a privileged type-label (case-insensitive). */
    public static boolean isTypeLabel(String name) {
        return name != null && ALL.contains(name.toUpperCase());
    }

    /**
     * The type-label a persisted node must carry, derived from its concrete entity type — so the
     * label always matches the discriminator regardless of what a caller sends. Empty for a plain
     * {@link ResourceEntity}, which has no type-label.
     */
    public static Optional<String> forEntity(NodeEntity node) {
        if (node instanceof AssetEntity) return Optional.of(ASSET);
        if (node instanceof DatasetEntity) return Optional.of(DATASET);
        if (node instanceof PolicyEntity) return Optional.of(POLICY);
        if (node instanceof TimeseriesEntity) return Optional.of(TIMESERIES);
        if (node instanceof FunctionEntity) return Optional.of(FUNCTION);
        return Optional.empty();
    }
}
