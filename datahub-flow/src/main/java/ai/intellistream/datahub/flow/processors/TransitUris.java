// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The transit URIs of DataHub's own data, in the fixed form the platform parses (the records table
 * derives the entity, external id and window from them). External ids are percent-encoded; the
 * tenant is implicit.
 */
final class TransitUris {

    private TransitUris() {
    }

    /** {@code datahub://timeseries/{externalId}?from={instant}&to={instant}} */
    static String timeseries(String externalId, Instant from, Instant to) {
        return "datahub://timeseries/" + encode(externalId) + "?from=" + from + "&to=" + to;
    }

    /** {@code datahub://events?type={type}&subType={subType}&from={instant}&to={instant}} */
    static String events(String type, String subType, Instant from, Instant to) {
        return "datahub://events?type=" + encode(type)
                + (subType == null ? "" : "&subType=" + encode(subType))
                + "&from=" + from + "&to=" + to;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
