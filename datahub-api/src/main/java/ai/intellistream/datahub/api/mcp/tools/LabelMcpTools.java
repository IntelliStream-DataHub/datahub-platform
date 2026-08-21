// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.LeanLabel;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.services.LabelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class LabelMcpTools {

    private final LabelService labelService;

    public LabelMcpTools(LabelService labelService) {
        this.labelService = labelService;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "label_list",
            description = """
                    List labels defined for the tenant. Labels are short categorical tags
                    (e.g. 'PRODUCTION', 'NIFI_FUNCTION') attached to resources and timeseries.
                    The catalogue is small; 'truncated' is set on the rare overflow.
                    """
    )
    public McpList<LeanLabel> listLabels(
            @ToolParam(required = false, description = "Max results (default 500).")
            Integer limit
    ) {
        int cap = (limit == null) ? 500 : limit;
        List<LeanLabel> items = labelService.list().stream()
                .map(LeanLabel::from)
                .limit(cap)
                .toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "label_create",
            description = """
                    Create one label. Name is coerced to SCREAMING_SNAKE_CASE server-side.
                    """
    )
    public DataWrapper<Label> createLabel(
            @ToolParam(description = "Display name; normalised to SCREAMING_SNAKE_CASE.")
            String name,
            @ToolParam(required = false, description = "Optional description.")
            String description,
            @ToolParam(required = false, description = "Optional i18n code, e.g. 'nifi.function'.")
            String i18nCode,
            @ToolParam(required = false, description = "Optional UI color as hex string, e.g. '#cc11cc'.")
            String color
    ) {
        LabelForm form = new LabelForm();
        form.setName(name);
        if (description != null) form.setDescription(description);
        if (i18nCode != null) form.setI18nCode(i18nCode);
        if (color != null) form.setColor(color);

        DataWrapper<LabelForm> req = new DataWrapper<>();
        req.setItems(List.of(form));
        DataWrapper<Label> out = new DataWrapper<>();
        out.setItems(new ArrayList<>(labelService.createLabels(req)));
        return out;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "label_update",
            description = """
                    Update a single label's description / i18nCode / color by id. Name
                    changes are not supported — create a new label if you need a different
                    identifier.
                    """
    )
    public DataWrapper<Label> updateLabel(
            @ToolParam(description = "Id of the label to update.")
            Long id,
            @ToolParam(required = false, description = "New description.")
            String description,
            @ToolParam(required = false, description = "New i18n code.")
            String i18nCode,
            @ToolParam(required = false, description = "New color (hex).")
            String color
    ) {
        LabelForm form = new LabelForm();
        form.setId(id);
        if (description != null) form.setDescription(description);
        if (i18nCode != null) form.setI18nCode(i18nCode);
        if (color != null) form.setColor(color);

        DataWrapper<LabelForm> req = new DataWrapper<>();
        req.setItems(List.of(form));
        DataWrapper<Label> out = new DataWrapper<>();
        out.setItems(new ArrayList<>(labelService.updateLabels(req)));
        return out;
    }
}
