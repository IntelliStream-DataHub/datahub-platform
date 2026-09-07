// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extension points of the shared grant grammar. The behaviour of the grammar under its two
 * current subjects is pinned by {@link DatasetGrantsTest} and {@link SettingsGrantsTest}; this
 * covers what only shows up when a facade declares something new.
 */
class GrantGrammarTest {

    /** A grammar is bound to its verbs: a verb another subject knows grants nothing here. */
    @Test
    void aVerbIsOnlyGrantedIfTheGrammarDeclaresIt() {
        GrantGrammar grammar = GrantGrammar.of("streams", "read", "manage");
        GrantGrammar.Grants grants = grammar.parse(List.of(
                "/streams/plant-a/manage",
                "/streams/plant-a/write"));

        assertThat(grants.objects("manage")).containsExactly("plant-a");
        assertThat(grants.objects("write")).isEmpty();
        assertThat(grants.allowsAll("write")).isFalse();
    }

    /** Verbs never imply one another; a new one follows the same rule read and write do. */
    @Test
    void aNewVerbIsIndependentOfTheOthers() {
        GrantGrammar grammar = GrantGrammar.of("streams", "read", "manage");
        GrantGrammar.Grants grants = grammar.parse(List.of("/streams/*/manage"));

        assertThat(grants.allowsAll("manage")).isTrue();
        assertThat(grants.allowsAll("read")).isFalse();
        assertThat(grants.objects("read")).isEmpty();
    }

    /** Subjects are disjoint: each grammar sees only its own prefix in the one shared group list. */
    @Test
    void grammarsDoNotReadEachOthersSubjects() {
        List<String> groups = List.of("/streams/plant-a/read", "/datasets/plant-a/read");

        assertThat(GrantGrammar.of("streams", "read").parse(groups).objects("read"))
                .containsExactly("plant-a");
        assertThat(GrantGrammar.of("pipelines", "read").parse(groups).isEmpty()).isTrue();
    }
}
