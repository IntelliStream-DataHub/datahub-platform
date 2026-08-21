// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Serializes MCP tool results with {@link JsonInclude.Include#NON_EMPTY} inclusion so that
 * {@code null}s, empty collections/maps, and empty strings never reach the LLM's context
 * window — the same converter datahub-api's {@code McpResultConverter} applies on its tool
 * path. An analysis that was not run leaves whole blocks of {@code LeanAnalysisResult} null,
 * and this is what keeps those out of the payload.
 *
 * <p>Wired per method via {@code @Tool(resultConverter = AnalysisMcpResultConverter.class)}.
 */
public class AnalysisMcpResultConverter implements ToolCallResultConverter {

    /** Dates render as ISO-8601 strings, matching the REST responses of this service and the api. */
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
