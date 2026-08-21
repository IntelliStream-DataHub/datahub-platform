// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;

import ai.intellistream.datahub.jpa.domains.INode;

public interface INodeDownload {

    Long getId();
    String getName();
    String getExternalId();
    Long getSize();
    INode.INodeType getNodeType();
    String getMimeType();
    String getPath();

}
