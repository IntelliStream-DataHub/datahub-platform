// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "logout")
public class LogoutProperties {
    private Map<String, ProviderLogoutProperties> registration = new HashMap<>();

    @Data
    static class ProviderLogoutProperties {
        private URI logoutUri;
        private String postLogoutUriParameterName;
    }
}
