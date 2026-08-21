// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.errors;

import lombok.Getter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thrown when one or more entities cannot be deleted because other entities still reference them.
 * Carries every blocked entity together with the entities referencing it, so the api layer can
 * translate the whole set into its HTTP error contract without the service depending on
 * api-module classes.
 */
@Getter
public class EntityInUseException extends RuntimeException {

    /** A single entity that could not be deleted, plus the entities referencing it. */
    @Getter
    public static final class Blocked {
        private final String entityType;
        private final String entityName;
        /** Each referencing entity as an identifier map, e.g. {@code {externalId, id}}. */
        private final Collection<Map<String, String>> usages;

        public Blocked(String entityType, String entityName, Collection<Map<String, String>> usages) {
            this.entityType = entityType;
            this.entityName = entityName;
            this.usages = usages;
        }
    }

    private final List<Blocked> blocked;

    public EntityInUseException(List<Blocked> blocked) {
        super(buildMessage(blocked));
        this.blocked = List.copyOf(blocked);
    }

    /** Convenience for a single blocked entity. */
    public EntityInUseException(String entityType, String entityName, Collection<Map<String, String>> usages) {
        this(List.of(new Blocked(entityType, entityName, usages)));
    }

    private static String buildMessage(List<Blocked> blocked) {
        String names = blocked.stream()
                .map(b -> "%s [%s]".formatted(b.getEntityType(), b.getEntityName()))
                .collect(Collectors.joining(", "));
        return names + (blocked.size() == 1 ? " is" : " are")
                + " still being used and cannot be deleted.";
    }
}
