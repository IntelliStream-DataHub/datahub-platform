// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import java.util.Map;

public record GovernanceTemplateDTO(
        Long id,
        String externalId,
        String name,
        String description,
        Map<String, String> metadata
) {}
