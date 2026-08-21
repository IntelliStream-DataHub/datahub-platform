// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


public interface NameAndExternalId  {

    Long getId();
    String getName();
    String getExternalId();
    Long getExternalIdHash();

}
