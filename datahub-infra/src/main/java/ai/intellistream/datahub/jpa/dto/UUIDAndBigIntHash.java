// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import java.math.BigInteger;
import java.util.UUID;

public interface UUIDAndBigIntHash {

    UUID getId();
    void setId(UUID id);
    String getExternalId();
    void setExternalId(String externalId);
    BigInteger getExternalIdHash();
    void setExternalIdHash(BigInteger externalIdHash);

}
