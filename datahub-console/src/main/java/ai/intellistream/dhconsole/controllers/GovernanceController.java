// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Slf4j
@Controller
@RequestMapping("/governance")
@RequiredArgsConstructor
public class GovernanceController {

    private final DatahubApi datahubApi;

    @GetMapping("")
    public String index(Model model) {
        model.addAttribute("templates", datahubApi.getGovernanceTemplates().getItems());
        return "governance/index";  // Thymeleaf page
    }
}
