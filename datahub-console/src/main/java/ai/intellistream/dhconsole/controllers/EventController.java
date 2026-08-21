// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Controller
@RequestMapping(value = {"/events"})
@Slf4j
public class EventController {

    private final DatahubApi datahubApi;
    private final MessageSource messageSource;

    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    public EventController(DatahubApi datahubApi, MessageSource messageSource){
        this.datahubApi = datahubApi;
        this.messageSource = messageSource;
    }

    @RequestMapping(value = {"", "/", "/index"}, method = RequestMethod.GET)
    public String index(Model model, Locale locale){
        LocalDate defaultStartDate = LocalDate.now().minusMonths(1);
        LocalTime defaultTime = LocalTime.MIDNIGHT;

        model.addAttribute("defaultStartDate", defaultStartDate.format(dateFormatter));
        model.addAttribute("defaultTime", defaultTime.format(timeFormatter));
        return "events/index";
    }

}
