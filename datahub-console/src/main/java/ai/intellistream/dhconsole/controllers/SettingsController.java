// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the settings pages under {@code /settings} — the things an organization administers for
 * itself, reached from the user menu.
 *
 * <p>These are shells only. Every value on them is loaded and saved by the browser against
 * datahub-api directly, per CONSTRAINTS.md, so there is nothing to fetch here and no model to
 * populate.
 *
 * <p>The pages are served to anyone signed in, and the API refuses the calls behind them without
 * the {@code /settings/read} or {@code /settings/write} organization group. Hiding the entry
 * instead would mean a permissions lookup on every page render, to save a user who has no business
 * here from seeing a form that tells them so.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    /** The section a bare {@code /settings} lands on. */
    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/settings/ai";
    }

    @GetMapping("/ai")
    public String ai() {
        return "settings/ai";
    }
}
