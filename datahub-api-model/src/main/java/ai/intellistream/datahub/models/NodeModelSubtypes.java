// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.timeseries.Timeseries;
import tools.jackson.databind.module.SimpleModule;

import java.util.Map;

/**
 * Jackson module that makes the abstract {@link NodeModel} deserializable. The node family's
 * type-label <em>is</em> the wire discriminator — a node carries at most one, a node with none is
 * a plain {@link Resource} — so a heterogeneous payload needs no extra type property. Register
 * this module on every mapper that reads {@code NodeModel}-typed payloads: the api, the Java SDK
 * client, and the console's Feign decoder. Serialization needs nothing from it: each subtype
 * serializes its own shape and {@link NodeModel#setLabels} keeps the type-label present.
 */
public final class NodeModelSubtypes extends SimpleModule {

    /**
     * The label→DTO dispatch table — the wire-side counterpart of the entity-side
     * {@code TypeLabels.forEntity} in datahub-infra (which this module cannot see; a parity test
     * in datahub-api holds the two authorities equal). Keys are canonical type-label names.
     */
    public static final Map<String, Class<? extends NodeModel>> BY_TYPE_LABEL = Map.of(
            "ASSET", Asset.class,
            "TIMESERIES", Timeseries.class,
            "FUNCTION", Function.class,
            "DATASET", DataSetModel.class,
            "POLICY", Policy.class);

    public NodeModelSubtypes() {
        super("node-model-subtypes");
        addDeserializer(NodeModel.class, new NodeModelDeserializer());
    }
}
