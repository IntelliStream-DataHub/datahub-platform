// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.spring;

import lombok.Setter;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Set from AppConfig class
 */
public class EnvUtils {

    @Setter
    private static Environment environment;

    public static boolean isDevProfileActive() {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }
}
