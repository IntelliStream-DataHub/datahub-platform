// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import lombok.Data;
import lombok.Setter;

import java.math.BigInteger;
import java.util.UUID;

@Setter
@Data
public class UUIDAndBigIntHashImpl implements UUIDAndBigIntHash {

    private UUID id;
    private String externalId;
    private BigInteger externalIdHash;

}
