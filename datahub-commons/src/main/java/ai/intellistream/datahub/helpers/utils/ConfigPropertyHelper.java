// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import org.springframework.core.env.Environment;

import java.util.Optional;

public class ConfigPropertyHelper {

    public static int getIntProperty(Environment env, String propertyPath, int defaultValue) {
        return Optional.ofNullable(env.getProperty(propertyPath))
                .map(str -> {
                    try {
                        return Integer.parseInt(str);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(defaultValue);
    }

}
