// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import java.util.Collection;

public interface EdgeRepo {

    void deleteAllByNodeIds(Collection<Long> idList);
}
