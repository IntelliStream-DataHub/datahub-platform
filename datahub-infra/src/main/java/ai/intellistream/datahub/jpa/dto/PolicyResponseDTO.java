// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;

import java.time.ZonedDateTime;
import java.util.Map;

public record PolicyResponseDTO(
        Long id,
        String label,
        String nodeType,
        Map<String, String> metadata,
        ZonedDateTime createdAt
) {}
