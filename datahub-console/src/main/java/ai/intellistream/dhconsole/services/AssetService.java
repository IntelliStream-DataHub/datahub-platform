// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Resolves the {@code <script>} sources for a declared asset bundle (used by the
 * {@code fragments/assets :: js(bundle)} Thymeleaf fragment).
 *
 * <p>Bundles are declared <b>once</b> in {@code assets.gradle}, which also parses the manifests
 * and emits {@code asset-bundles.properties} — the single source of truth this class reads. The
 * Java side neither re-declares bundles nor parses manifests. See ASSETS.md.
 *
 * <ul>
 *   <li><b>Production</b> (default): the single minified bundle URL.</li>
 *   <li><b>Development</b> ({@code datahub.assets.unbundled=true}): the original source files in
 *       order, so editing one and refreshing shows the change immediately (no rebuild).</li>
 * </ul>
 */
@Service
public class AssetService {

    private static final String REGISTRY = "asset-bundles.properties";

    private final boolean unbundled;
    private final Properties registry;

    public AssetService(@Value("${datahub.assets.unbundled:false}") boolean unbundled) {
        this.unbundled = unbundled;
        this.registry = loadRegistry();
    }

    /** Ordered web paths of the {@code <script>} tags to emit for the given bundle. */
    public List<String> scripts(String bundleName) {
        String bundleUrl = registry.getProperty(bundleName + ".bundle");
        if (bundleUrl == null) {
            throw new IllegalArgumentException("Unknown asset bundle: " + bundleName);
        }
        if (!unbundled) {
            return List.of(bundleUrl);
        }
        String sources = registry.getProperty(bundleName + ".sources", "");
        return sources.isBlank() ? List.of() : Arrays.asList(sources.split(","));
    }

    private static Properties loadRegistry() {
        Properties props = new Properties();
        ClassPathResource resource = new ClassPathResource(REGISTRY);
        try (InputStream in = resource.getInputStream()) {
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException(
                    REGISTRY + " not found on the classpath — build the assets first "
                    + "(./gradlew :datahub-console:buildAssets).", e);
        }
        return props;
    }
}
