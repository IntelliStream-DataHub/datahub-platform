// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.dhconsole.config.FlowAccess;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the Agents section: shells only, as {@link FlowsController} does. An agent is a flow of
 * kind {@code agent}, so every page reads and writes it against datahub-flow from the browser, and
 * the attention list against datahub-api's events. The deploy round-trip lands on
 * {@code /agents/<externalId>} (datahub-flow's {@code datahub.flow.console-url}), so that path is
 * fixed; the other views hang off {@code /agents} by query parameter for the same reason as in Flows.
 *
 * <p>A tenant without flows, or without a usable model, sees a notice instead of the controls.
 */
@Controller
@RequestMapping("/agents")
public class AgentsController {

    private final FlowAccess flowAccess;

    public AgentsController(FlowAccess flowAccess) {
        this.flowAccess = flowAccess;
    }

    @GetMapping
    public String index(Model model) {
        return page(model, "agents/index");
    }

    /** An agent not saved yet: it gets its external id from the name typed in the form. */
    @GetMapping(params = "new")
    public String create(Model model) {
        model.addAttribute("flowExternalId", "");
        return page(model, "agents/agent");
    }

    @GetMapping(params = "tab=runs")
    public String runs(Model model) {
        return page(model, "agents/runs");
    }

    @GetMapping(params = "tab=attention")
    public String attention(Model model) {
        return page(model, "agents/attention");
    }

    /** The tool servers the organization's agents may use beyond DataHub's own: registered here, kept in datahub-api's tenant settings like the flow clients. */
    @GetMapping(params = "tab=servers")
    public String servers(Model model) {
        return page(model, "agents/servers");
    }

    /** One agent: its form, deployment, runs and what it asked a person to look at. */
    @GetMapping("/{externalId}")
    public String agent(@PathVariable String externalId, Model model) {
        model.addAttribute("flowExternalId", externalId);
        return page(model, "agents/agent");
    }

    @GetMapping("/{externalId}/runs/{runId}")
    public String run(@PathVariable String externalId, @PathVariable String runId, Model model) {
        model.addAttribute("flowExternalId", externalId);
        model.addAttribute("runId", runId);
        return page(model, "agents/run");
    }

    private String page(Model model, String view) {
        model.addAttribute("featureDisabled", !flowAccess.agentsAvailable());
        return view;
    }
}
