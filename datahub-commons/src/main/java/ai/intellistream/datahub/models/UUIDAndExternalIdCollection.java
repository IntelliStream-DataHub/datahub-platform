// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models;

import lombok.Data;

import java.util.UUID;

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
