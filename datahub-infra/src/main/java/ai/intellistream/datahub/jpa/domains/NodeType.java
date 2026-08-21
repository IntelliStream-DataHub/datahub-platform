// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "node_type")
@Getter @Setter
public class NodeType {

    public static final long ASSET = 1;
    public static final long TIMESERIES = 2;
    public static final long FUNCTION = 3;
    public static final long RESOURCE = 4;
    public static final long DATASET = 5;
    public static final long POLICY = 6;

    /**
     * The names a caller may use for these ids, for the generic node query's {@code nodeType}
     * filter. Here beside the constants so the two cannot drift: a name that stops matching its id
     * would silently filter to the wrong type rather than fail.
     */
    private static final Map<String, Long> BY_NAME = Map.of(
            "asset", ASSET,
            "timeseries", TIMESERIES,
            "function", FUNCTION,
            "resource", RESOURCE,
            "dataset", DATASET,
            "policy", POLICY);

    /**
     * The ids behind a list of caller-supplied type names, case-insensitively.
     *
     * <p>A name matching nothing is dropped. That means a list of only unknown names resolves to an
     * empty set, which the caller must read as "match nothing" rather than "no restriction" — they
     * asked to be narrowed to those types, and answering with every type would be the opposite.
     */
    public static Set<Long> idsForNames(Collection<String> names) {
        if (names == null) {
            return Set.of();
        }
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> BY_NAME.get(name.trim().toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min = 3, max = 512)
    private String name;

    @Null
    private String description;

    @CreationTimestamp
    private ZonedDateTime dateCreated;

    @UpdateTimestamp
    private ZonedDateTime lastUpdated;

}
