// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.files;

import ai.intellistream.datahub.jpa.dto.INodeProxy;

import java.util.List;
import java.util.Set;

public interface IINodeRepo {

    List<INodeProxy> findAllByIdAndExternalIdAndNotDeleted(Set<Long> idList, Set<String> externalIdList);

    void flush();

}
