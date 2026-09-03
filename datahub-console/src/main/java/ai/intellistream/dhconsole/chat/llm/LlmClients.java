// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.ChatSettings;

/** Resolves the model client for a turn. An interface so a test can supply a stub. */
@FunctionalInterface
public interface LlmClients {

    /**
     * @throws IllegalStateException if the settings name a backend that cannot be used, with a
     *                               message naming what to configure
     */
    LlmClient forSettings(ChatSettings settings);
}
