// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;

/**
 * A Function is a plain datastore node on the shared {@code node} table (single-table
 * inheritance, {@code node_type = 3}), carrying only the common node fields. It behaves
 * exactly like a {@link ResourceEntity}; the {@code FUNCTION} type-label is what tells
 * the two apart.
 */
@Entity
@DiscriminatorValue("3")
@Getter
@Setter
public class FunctionEntity extends NodeEntity {
}
