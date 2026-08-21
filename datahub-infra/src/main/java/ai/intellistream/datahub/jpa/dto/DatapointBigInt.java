// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import java.time.LocalDateTime;

public interface DatapointBigInt {

    LocalDateTime getTimestamp();
    Long getValue();

}
