// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import java.util.Map;

/**
 * An offer to open what the assistant just looked up in the console UI.
 *
 * <p>Deliberately not a URL. The console keeps state out of the address bar, so the panel hands
 * this object to the target page through {@code sessionStorage} and navigates to a clean path. That
 * also means nothing here has to be encoded or formatted for a URL — the page receives the raw
 * values and maps them to its own controls.
 *
 * @param page   which console page can show this, e.g. {@code "events"}
 * @param filter the filter to apply, in the tool's own vocabulary (the page maps it to its form)
 * @param count  how many rows matched, when the tool result said; {@code null} otherwise
 */
public record ConsoleView(String page, Map<String, Object> filter, Integer count) {
}
