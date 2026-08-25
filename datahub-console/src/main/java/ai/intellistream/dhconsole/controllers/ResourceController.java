// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.api.error.ConflictException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Locale;

@Controller
@RequestMapping(value = {"/resources"})
@Slf4j
public class ResourceController {

    private final DatahubApi datahubApi;
    private final MessageSource messageSource;

    public ResourceController(DatahubApi datahubApi, MessageSource messageSource){
        this.datahubApi = datahubApi;
        this.messageSource = messageSource;
    }

    @RequestMapping(value = {"", "/", "/index"}, method = RequestMethod.GET)
    public String index(Model model, Locale locale){
        ResourceRetreiver retreiver = new ResourceRetreiver();
        retreiver.getFilter().setIsRoot(true);
        try {
            DataWrapper<ai.intellistream.datahub.models.NodeModel> rootResources = datahubApi.filter(retreiver);
            if(rootResources.getItems().isEmpty()){
                model.addAttribute("flashError",
                        messageSource.getMessage("no.root.resources.found", null, locale));
            } else {
                model.addAttribute("rootResources", rootResources.getItems());
            }
        } catch (ConflictException | FeignException e){
            // DataHubErrorDecoder turns every non-2xx API response into a ConflictException,
            // so catching FeignException alone let API errors escape as a Whitelabel 500.
            log.error(e.getMessage());
        }
        return "resources/index";
    }

}
