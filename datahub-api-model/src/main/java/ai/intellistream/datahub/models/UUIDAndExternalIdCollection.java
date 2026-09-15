// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import lombok.Data;

import java.util.UUID;

/**
 * A reference to an event, by its UUID {@code id}, its {@code externalId}, or both.
 *
 * <p>The event-shaped counterpart of {@link IdCollection}, whose {@code id} is a {@code Long}
 * because it addresses a node. An event id is a UUID, so the two are not interchangeable.
 *
 * <p>Here rather than in datahub-commons because it is a request body on {@code POST /events/byids}
 * and {@code POST /events/delete} — part of the wire contract, which is what this module is. While
 * it lived in commons the Apache-2.0 Java SDK had no way to reference it, and reached for
 * {@link IdCollection} instead: a {@code Long} id where the endpoint expects a UUID, so the SDK's
 * by-id path could not work at all.
 */
@Data
public class UUIDAndExternalIdCollection {

    private UUID id;

    private String externalId;

    public static UUIDAndExternalIdCollection createFromExternalId(String id){
        var instance = new UUIDAndExternalIdCollection();
        instance.externalId = id;
        return instance;
    }

    public String getExternalId(){
        return this.externalId;
    }

}
