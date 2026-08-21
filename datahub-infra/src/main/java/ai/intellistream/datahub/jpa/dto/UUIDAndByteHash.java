// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import java.util.UUID;

public interface UUIDAndByteHash {

    UUID getId();
    void setId(UUID id);
    String getExternalId();
    void setExternalId(String externalId);
    byte[] getExternalIdHash();
    void setExternalIdHash(byte[] externalIdHash);

}
