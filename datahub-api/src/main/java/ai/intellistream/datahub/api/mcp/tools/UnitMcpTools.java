// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp.tools;

import ai.intellistream.datahub.api.mcp.McpResultConverter;
import ai.intellistream.datahub.api.mcp.dto.LeanUnit;
import ai.intellistream.datahub.api.mcp.dto.McpList;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.UnitService;
import ai.intellistream.datahub.jpa.domains.Unit;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.transformers.UnitEntityTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
@Slf4j
public class UnitMcpTools {

    private final UnitService unitService;

    public UnitMcpTools(UnitService unitService) {
        this.unitService = unitService;
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "unit_list",
            description = """
                    List units of measure known to DataHub (e.g. Celsius, Litres per Second).
                    The catalogue is small (typical ~100s) and managed centrally. Use the
                    returned externalId when creating a timeseries with a physical unit.
                    """
    )
    public McpList<LeanUnit> listUnits(
            @ToolParam(required = false, description = "Max results (default 1000).")
            Integer limit
    ) {
        int cap = (limit == null) ? 1000 : limit;
        Collection<Unit> units = unitService.list(cap);
        List<LeanUnit> items = UnitEntityTransformer.toUnit(units).stream()
                .map(LeanUnit::from).toList();
        return McpList.of(items, cap);
    }

    @Tool(
            resultConverter = McpResultConverter.class,
            name = "unit_get",
            description = """
                    Look up a single unit by its externalId (e.g. 'celsius',
                    'metres_per_second'). Returns an empty items list if no match.
                    """
    )
    public DataWrapper<UnitModel> getUnit(
            @ToolParam(description = "Stable externalId of the unit.")
            String externalId
    ) {
        Unit u = unitService.findByExternalId(externalId);
        DataWrapper<UnitModel> out = new DataWrapper<>();
        if (u != null) {
            out.setItems(UnitEntityTransformer.toUnit(List.of(u)));
        }
        return out;
    }
}
