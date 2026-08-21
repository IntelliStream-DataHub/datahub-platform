// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


public interface ExternalId {

    Long getId();
    String getExternalId();
    Long getExternalIdHash();

}
