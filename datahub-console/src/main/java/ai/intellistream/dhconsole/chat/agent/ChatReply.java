// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import java.util.List;

/**
 * What one user turn produced.
 *
 * <p>{@code toolsUsed} is for the panel's "looked at dataset_list, timeseries_search" trace. It is
 * the visible substitute for streaming: the user can't watch the work happen, so they at least see
 * what was consulted.
 *
 * <p>{@code views} are offers to open the same lookup in the console UI — see {@link ConsoleView}.
 */
public record ChatReply(String reply, List<String> toolsUsed, List<ConsoleView> views, boolean truncated) {
}
