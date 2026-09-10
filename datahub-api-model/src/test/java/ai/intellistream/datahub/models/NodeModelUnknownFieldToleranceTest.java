// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins that a label-dispatched subtype tolerates fields it does not declare. The flat write
 * echoes (create/update/delete) serialize every node as a Resource — including {@code isRoot}
 * on types that don't carry it — and clients bind those bodies against {@code NodeModel}, so
 * the dispatch target must ignore the extras rather than reject the body.
 */
class NodeModelUnknownFieldToleranceTest {

    @Test
    void aDispatchedSubtypeIgnoresUndeclaredFields() {
        JsonMapper mapper = JsonMapper.builder().addModule(new NodeModelSubtypes()).build();

        NodeModel bound = mapper.readValue("""
                {"externalId":"plant_data","name":"Plant data","labels":["DATASET"],"isRoot":false}
                """, NodeModel.class);

        assertInstanceOf(DataSetModel.class, bound);
    }
}
