// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import lombok.Data;
import lombok.Setter;

import java.util.UUID;

@Setter
@Data
public class UUIDAndByteHashImpl implements UUIDAndByteHash {

    private UUID id;
    private String externalId;
    private byte[] externalIdHash;

}
