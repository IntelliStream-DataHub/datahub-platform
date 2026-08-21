// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.dhconsole.i18n.I18NHelper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Serves the raw, locale-resolved messages.properties so the browser can seed
 * its shared $L cache from one place instead of each page injecting its own
 * messages. The client parses the key=value lines.
 */
@RestController
@RequestMapping("/api/i18n")
public class I18nApiController {

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public void messages(HttpServletResponse response, Locale locale) {
        response.setStatus(200);
        I18NHelper.loadMessages(response, locale);
    }
}
