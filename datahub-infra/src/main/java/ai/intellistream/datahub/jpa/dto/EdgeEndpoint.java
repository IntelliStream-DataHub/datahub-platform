// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;

/**
 * Just enough of an edge endpoint to validate what may connect to what: the node's type and,
 * for timeseries, which dataset it already belongs to. Spring Data interface projection —
 * use with {@code GenericNodeRepository.findById(id, EdgeEndpoint.class)}.
 */
public interface EdgeEndpoint {

    Long getId();

    NodeTypeId getNodeType();

    DataSetId getDataSet();

    interface NodeTypeId {
        Long getId();
    }

    interface DataSetId {
        Long getId();
    }
}
