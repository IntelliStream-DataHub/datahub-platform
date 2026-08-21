// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers;

public class EventHelper {

    public static String[] EVENT_COLUMNS = {
            "e.id as id",
            "e.external_id as external_id",
            "e.external_id_hash as external_id_hash",
            "e.type as type",
            "e.sub_type as sub_type",
            "e.status as status",
            "e.description as description",
            "e.data_set_id as data_set_id",
            "e.source as source",
            "e.date_created as date_created",
            "e.last_updated as last_updated",
            "e.event_time as event_time",
            "e.related_resources_id as related_resources_id",
            "e.related_resources_external_id as related_resources_external_id",
            "e.metadata as metadata"
    };

    public static String[] EVENT_ONLY_ID_COLUMNS = {
            "e.id as id",
            "e.external_id as external_id",
            "e.external_id_hash as external_id_hash"
    };
}
