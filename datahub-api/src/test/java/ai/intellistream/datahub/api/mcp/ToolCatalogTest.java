// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp;

import ai.intellistream.datahub.agent.ToolCapability;
import ai.intellistream.datahub.agent.ToolCatalogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link ToolCatalog} honest against the tools it claims to describe.
 *
 * <p>The catalogue is hand-written, because whether a tool reads or writes cannot be derived from
 * anything Spring AI exposes. Hand-written and load-bearing is a bad combination, so this test
 * reflects over every {@code @Tool} method actually on the classpath and fails if the two ever
 * disagree in either direction: an unclassified tool (which would be silently refused everywhere)
 * or a classified name that no longer exists (a rename that would silently remove it).
 */
class ToolCatalogTest {

    private static final String TOOLS_PACKAGE = "ai.intellistream.datahub.api.mcp.tools";

    private final ToolCatalog catalog = new ToolCatalog();

    /** Every {@code @Tool} method name declared in this service, found by scanning, not by a list. */
    private static Set<String> declaredToolNames() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));

        Set<String> names = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents(TOOLS_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("Scanned a class that will not load: "
                        + candidate.getBeanClassName(), e);
            }
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    names.add(tool.name());
                }
            }
        }
        return names;
    }

    @Test
    void everyToolThisServiceServesIsClassified() {
        Set<String> declared = declaredToolNames();

        // Sanity: if the scan finds nothing the assertions below pass vacuously and this test
        // would quietly stop protecting anything.
        assertThat(declared).hasSizeGreaterThan(30);

        assertThat(declared)
                .as("every @Tool in %s must be classified in ToolCatalog — an unclassified tool "
                        + "is refused everywhere, since an unknown name is not read-only", TOOLS_PACKAGE)
                .allSatisfy(name -> assertThat(catalog.isKnown(name)).as(name).isTrue());
    }

    @Test
    void theCatalogueNamesNoToolThatHasBeenRenamedOrRemoved() {
        Set<String> declared = declaredToolNames();

        Set<String> catalogued = catalog.entries().stream()
                .filter(e -> ToolCatalog.DATAHUB_API.equals(e.server()))
                .map(ToolCatalogEntry::name)
                .collect(TreeSet::new, Set::add, Set::addAll);

        assertThat(catalogued)
                .as("a catalogued name with no @Tool behind it is a rename that silently removed "
                        + "the tool from every agent allowlist naming it")
                .isEqualTo(declared);
    }

    @Test
    void anUnknownNameIsNotReadOnly() {
        // Default-deny: the safe answer for a tool nobody classified is "no", not "probably fine".
        assertThat(catalog.isKnown("resource_teleport")).isFalse();
        assertThat(catalog.isReadOnly("resource_teleport")).isFalse();
    }

    @Test
    void mutatingToolsAreNotReadOnly() {
        assertThat(catalog.isReadOnly("resource_create")).isFalse();
        assertThat(catalog.isReadOnly("event_delete")).isFalse();
        assertThat(catalog.isReadOnly("timeseries_send_datapoint")).isFalse();
        assertThat(catalog.isReadOnly("dataset_update")).isFalse();
    }

    @Test
    void theReadOnlySetIsTheOneTheAssistantHasAlwaysHad() {
        // The exact 20 names ToolPolicy hardcoded before the catalogue existed. Pinned so the
        // move from console to api provably changed nothing about what the assistant may do.
        assertThat(catalog.readOnlyToolNames()).containsExactlyInAnyOrder(
                "analysis_related_series",
                "dataset_list",
                "dataset_search",
                "edge_get",
                "edge_list_types",
                "event_filter",
                "event_get",
                "event_search",
                "label_list",
                "resource_fetch_nearest",
                "resource_fetch_related",
                "resource_get",
                "resource_search",
                "timeseries_fetch_datapoints",
                "timeseries_get",
                "timeseries_get_latest",
                "timeseries_list",
                "timeseries_search",
                "unit_get",
                "unit_list");
    }

    @Test
    void theAnalysisToolIsCataloguedAgainstItsOwnServer() {
        // It lives in datahub-analysis, but is catalogued here so one place can validate an
        // agent's allowlist. Its server field is what explains it vanishing when that service is
        // down, rather than looking like a bad allowlist.
        ToolCatalogEntry entry = catalog.entries().stream()
                .filter(e -> e.name().equals("analysis_related_series"))
                .findFirst().orElseThrow();

        assertThat(entry.server()).isEqualTo(ToolCatalog.DATAHUB_ANALYSIS);
        assertThat(entry.capability()).isEqualTo(ToolCapability.READ);
    }
}
