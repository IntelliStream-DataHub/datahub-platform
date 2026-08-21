// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Serializes MCP tool results with {@link JsonInclude.Include#NON_EMPTY} inclusion so that
 * {@code null}s, empty collections/maps, and empty strings never reach the LLM's context
 * window. The REST API's shared DTOs (e.g. {@code Timeseries}, {@code Resource}) serialize
 * with the default {@code ALWAYS} inclusion for their wire contract; this converter changes
 * nothing there — it only applies on the MCP tool path, wired per method via
 * {@code @Tool(resultConverter = McpResultConverter.class)}.
 *
 * <p>Reference this from <em>every</em> {@code @Tool}: it is the universal floor that trims
 * the full objects the {@code *_create}/{@code *_update} tools echo back and the {@code *_get}
 * detail tools return. The high-volume list/search tools go further and return purpose-built
 * lean projections (see {@code ai.intellistream.datahub.api.mcp.dto}).
 */
public class McpResultConverter implements ToolCallResultConverter {

    /**
     * Dates render as ISO-8601 strings (WRITE_DATES_AS_TIMESTAMPS disabled) to match the
     * {@code spring.jackson.datetime-feature.write-dates-as-timestamps: false} the rest of the
     * app runs with, so timestamps read the same over MCP as over REST.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_EMPTY))
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Override
    public String convert(Object result, Type returnType) {
        if (returnType == Void.TYPE) {
            return "Done";
        }
        return MAPPER.writeValueAsString(result);
    }
}
