// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * Which server to ask for its models, for {@code POST /tenant/settings/llm/models}.
 *
 * <p>A body rather than query parameters because of {@link #apiKey}: a credential in a URL ends up
 * in access logs. Absent or empty uses the key already stored for this organization.
 *
 * @param baseUrl the OpenAI-compatible server, as it would be saved, e.g. {@code http://localhost:11434/v1}
 * @param apiKey  a key to list with, if the server needs one and it is not the stored one
 */
public record TenantLlmModelsQuery(String baseUrl, String apiKey) {
}
