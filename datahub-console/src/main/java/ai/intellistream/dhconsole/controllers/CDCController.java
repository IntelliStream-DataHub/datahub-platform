// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.forms.cdc.CDCIntegrationForm;
import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Locale;

/**
 * Serves the Change-Data-Capture pages under {@code /cdc}. This is all that remains of the
 * former {@code /stream} section after the Pulsar stream-management feature
 * (namespaces/topics/roles) was removed.
 */
@Controller
@RequestMapping(value = {"/cdc"})
@Slf4j
public class CDCController {

    private final DatahubApi datahubApi;

    public CDCController(DatahubApi datahubApi){
        this.datahubApi = datahubApi;
    }

    @RequestMapping(value = {"", "/"}, method = RequestMethod.GET)
    public String cdc(Model model, Locale locale){
        if (streamingFeatureDisabled(model)) {
            return "cdc/cdc";
        }
        DataWrapper<Object> cdcIntegrations = new DataWrapper<>();
        model.addAttribute("cdcIntegrations",cdcIntegrations);
        return "cdc/cdc";
    }

    @RequestMapping(value = { "/create"}, method = RequestMethod.GET)
    public String createCDCIntegration(Model model, Locale locale){
        if (streamingFeatureDisabled(model)) {
            return "cdc/cdc-create";
        }
        CDCIntegrationForm form = new CDCIntegrationForm();
        model.addAttribute("form",form);
        return "cdc/cdc-create";
    }

    private boolean streamingFeatureDisabled(Model model) {
        boolean disabled = !datahubApi.getTenantFeatures().isStreamingFeatureEnabled();
        model.addAttribute("featureDisabled", disabled);
        return disabled;
    }

}
