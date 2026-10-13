// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.dhconsole.config.FlowAccess;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the Flows section: shells only. Every flow, run and record on these pages is read and
 * written by the browser against datahub-flow directly, and the Needs-attention list against
 * datahub-api's events, per CONSTRAINTS.md, so nothing is fetched here.
 *
 * <p>The section's other views hang off {@code /flows} by a query parameter rather than a path
 * segment: {@code /flows/<externalId>} is a flow, and a flow may be called {@code attention}. The
 * deploy round-trip lands on {@code /flows/<externalId>} too (datahub-flow's
 * {@code datahub.flow.console-url}), so that path is fixed.
 *
 * <p>The pages are served to anyone signed in. A tenant without flows sees a notice instead of the
 * page's controls; the service would refuse every call anyway.
 */
@Controller
@RequestMapping("/flows")
public class FlowsController {

    private final FlowAccess flowAccess;

    public FlowsController(FlowAccess flowAccess) {
        this.flowAccess = flowAccess;
    }

    @GetMapping
    public String index(Model model) {
        return page(model, "flows/index");
    }

    /** A flow not saved yet: it gets its external id from the name typed in the editor. */
    @GetMapping(params = "new")
    public String create(Model model) {
        model.addAttribute("flowExternalId", "");
        return page(model, "flows/flow");
    }

    @GetMapping(params = "tab=attention")
    public String attention(Model model) {
        return page(model, "flows/attention");
    }

    @GetMapping(params = "tab=lookup")
    public String lookup(Model model) {
        return page(model, "flows/lookup");
    }

    /** One flow: its processing tree, deployment and runs. An unknown id opens a new flow. */
    @GetMapping("/{externalId}")
    public String flow(@PathVariable String externalId, Model model) {
        model.addAttribute("flowExternalId", externalId);
        return page(model, "flows/flow");
    }

    @GetMapping("/{externalId}/runs/{runId}")
    public String run(@PathVariable String externalId, @PathVariable String runId, Model model) {
        model.addAttribute("flowExternalId", externalId);
        model.addAttribute("runId", runId);
        return page(model, "flows/run");
    }

    private String page(Model model, String view) {
        model.addAttribute("featureDisabled", !flowAccess.available());
        return view;
    }
}
