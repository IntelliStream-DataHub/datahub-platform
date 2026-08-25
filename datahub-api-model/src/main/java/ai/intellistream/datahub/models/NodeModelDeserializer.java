// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.helpers.text.TextValidator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Label-keyed polymorphic deserialization for the abstract {@link NodeModel}. Reads the body's
 * {@code labels}, canonicalises each name with the same rule the label store applies on persist
 * ({@link TextValidator#toSnakeUpperCased}), and dispatches on the type-label found: exactly one
 * → that subtype, none → {@link Resource}, more than one → input mismatch (a 400 at the API
 * boundary). Registered for the base type only, so a body bound against a concrete subtype never
 * passes through here (pinned by {@code NodeModelConcreteBindingTest}).
 */
final class NodeModelDeserializer extends ValueDeserializer<NodeModel> {

    @Override
    public NodeModel deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode tree = ctxt.readTree(p);
        Set<String> types = new LinkedHashSet<>();
        JsonNode labels = tree.path("labels");
        if (labels.isArray()) {
            for (JsonNode label : labels) {
                if (!label.isString()) {
                    continue;
                }
                String canonical = TextValidator.toSnakeUpperCased(label.asString());
                if (canonical != null && NodeModelSubtypes.BY_TYPE_LABEL.containsKey(canonical)) {
                    types.add(canonical);
                }
            }
        }
        if (types.size() > 1) {
            ctxt.reportInputMismatch(NodeModel.class,
                    "A node may have at most one type-label (one of %s); got %s",
                    NodeModelSubtypes.BY_TYPE_LABEL.keySet(), types);
        }
        Class<? extends NodeModel> target = types.isEmpty()
                ? Resource.class
                : NodeModelSubtypes.BY_TYPE_LABEL.get(types.iterator().next());
        return ctxt.readTreeAsValue(tree, target);
    }
}
