// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the invariant that a {@link RelationshipType} can never be created with a blank or
 * meaningless name. {@code setName} is the single chokepoint every creation path funnels through
 * (REST, MCP tool, find-or-create, function bindings), so rejecting blanks here protects them all
 * and prevents the {@code relationship_hash_key} collision a blank name would cause.
 */
class RelationshipTypeTest {

    @Test
    void setName_nullName_throws() {
        RelationshipType rt = new RelationshipType();
        assertThrows(IllegalArgumentException.class, () -> rt.setName(null));
    }

    @Test
    void setName_emptyName_throws() {
        RelationshipType rt = new RelationshipType();
        assertThrows(IllegalArgumentException.class, () -> rt.setName(""));
    }

    @Test
    void setName_whitespaceOnlyName_throws() {
        RelationshipType rt = new RelationshipType();
        assertThrows(IllegalArgumentException.class, () -> rt.setName("   "));
    }

    @Test
    void setName_symbolOnlyName_throws() {
        RelationshipType rt = new RelationshipType();
        assertThrows(IllegalArgumentException.class, () -> rt.setName("@#$"));
    }

    @Test
    void setName_validName_uppercasesAndHashes() {
        RelationshipType rt = new RelationshipType();
        rt.setName("flows_to");
        assertEquals("FLOWS_TO", rt.getName());
        assertNotNull(rt.getHash());
    }
}
