// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.me;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code GET /me/grants} answers: the caller's organization groups in the current tenant.
 *
 * <p>{@code groups} is every group path the caller holds there, unfiltered — dataset grants,
 * settings grants and anything else the organization defines — so that a client comparing two
 * callers' grants compares the whole set. {@code admin} is the {@code DATAHUB_ADMIN} realm role,
 * which is read from the token and is never a group: an admin may hold no groups at all and still
 * reach everything.
 */
public record MeGrants(
        @Schema(description = "Whether the caller holds the cross-tenant DATAHUB_ADMIN realm role.")
        boolean admin,
        @Schema(description = "The caller's organization group paths in this tenant.",
                example = "[\"/datasets/*/read\", \"/settings/llm/write\"]")
        List<String> groups) {
}
