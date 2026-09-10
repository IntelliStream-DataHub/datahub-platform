// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services.graph;

import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that knows how a {@link GraphSyncCommand} is encoded in the outbox column.
 *
 * <p>Its own mapper rather than the application's: the payload is written and read by this
 * codebase alone, so it neither wants the request-body mapper's strictness nor should it inherit
 * whatever a future web-layer setting decides. Jackson's default tolerance of unknown properties
 * is what lets a queued row survive a deploy that adds a field to the command.
 */
public final class GraphSyncCommandCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GraphSyncCommandCodec() {}

    public static String toJson(GraphSyncCommand command) {
        return JSON.writeValueAsString(command);
    }

    public static GraphSyncCommand fromJson(String json) {
        return JSON.readValue(json, GraphSyncCommand.class);
    }
}
