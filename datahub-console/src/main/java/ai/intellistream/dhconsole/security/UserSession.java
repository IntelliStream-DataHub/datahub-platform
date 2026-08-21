// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.io.Serial;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.Map;

@Component
@Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
@Getter
public class UserSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 2L;

    private String name;
    private String shortName;
    @Setter private String organizationId;
    @Setter private String organizationName;

    public UserSession() {
    }

    public void setName(String name) {
        this.name = name;
        this.shortName = deriveShortName(name);
    }

    /**
     * Extracts organization details from OIDC attributes.
     * Expected structure: { "organization": { "OrgName": { "id": "uuid" } } }
     */
    public void setOrganizationFromAttributes(Map<String, Object> attributes) {
        if (attributes == null) return;
        if (attributes.get("organization") instanceof Map<?, ?> orgs && !orgs.isEmpty()) {
            var first = orgs.entrySet().iterator().next();
            this.organizationName = String.valueOf(first.getKey());
            if (first.getValue() instanceof Map<?, ?> details) {
                this.organizationId = String.valueOf(details.get("id"));
            }
        }
    }

    private static String deriveShortName(String fullName) {
        if (fullName == null) return null;
        String normalized = Normalizer.normalize(fullName, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "");
        StringBuilder sb = new StringBuilder();
        for (String part : normalized.trim().split("\\s+")) {
            if (!part.isEmpty()) sb.append(part.charAt(0));
        }
        return sb.toString().toUpperCase();
    }
}
