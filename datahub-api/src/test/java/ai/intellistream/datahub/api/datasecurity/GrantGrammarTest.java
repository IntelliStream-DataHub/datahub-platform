// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extension points of the shared grant grammar. The behaviour of the grammar under its two
 * current subjects is pinned by {@link DatasetGrantsTest} and {@link SettingsGrantsTest}; this
 * covers what only shows up when a facade declares something different.
 */
class GrantGrammarTest {

    /** A grammar grants only the verbs it declares: WRITE exists platform-wide, not for this subject. */
    @Test
    void aVerbIsOnlyGrantedIfTheGrammarDeclaresIt() {
        GrantGrammar grammar = GrantGrammar.of("streams", GrantVerb.READ);
        GrantGrammar.Grants grants = grammar.parse(List.of(
                "/streams/plant-a/read",
                "/streams/plant-a/write",
                "/streams/*/write"));

        assertThat(grants.objects(GrantVerb.READ)).containsExactly("plant-a");
        assertThat(grants.objects(GrantVerb.WRITE)).isEmpty();
        assertThat(grants.allowsAll(GrantVerb.WRITE)).isFalse();
    }

    /** Subjects are disjoint: each grammar sees only its own prefix in the one shared group list. */
    @Test
    void grammarsDoNotReadEachOthersSubjects() {
        List<String> groups = List.of("/streams/plant-a/read", "/datasets/plant-a/read");

        assertThat(GrantGrammar.of("streams", GrantVerb.READ).parse(groups).objects(GrantVerb.READ))
                .containsExactly("plant-a");
        assertThat(GrantGrammar.of("pipelines", GrantVerb.READ).parse(groups).isEmpty()).isTrue();
    }
}
