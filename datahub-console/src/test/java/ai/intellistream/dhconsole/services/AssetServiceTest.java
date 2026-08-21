// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetServiceTest {

    @Test
    void productionReturnsTheSingleBundle() {
        assertEquals(
                List.of("/static/js/right-form.bundle.min.js"),
                new AssetService(false).scripts("rightForm"));
    }

    @Test
    void devReturnsManifestSourcesInOrder() {
        List<String> sources = new AssetService(true).scripts("rightForm");

        // base must be first; every right-form file resolved to a /static/js/... URL.
        assertEquals("/static/js/right-form-content/base_form_abstract.js", sources.get(0));
        assertEquals(7, sources.size());
        assertTrue(sources.contains("/static/js/right-form-content/resources/form.js"), sources.toString());
        assertTrue(sources.stream().allMatch(s -> s.startsWith("/static/js/right-form-content/")), sources.toString());
    }

    @Test
    void unknownBundleThrows() {
        assertThrows(IllegalArgumentException.class, () -> new AssetService(false).scripts("does-not-exist"));
    }
}
