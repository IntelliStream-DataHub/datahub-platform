// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a flow definition from JSON.
 *
 * <h2>Fields the format does not know</h2>
 * Rejected, at every level. A definition is a contract the service runs unattended, so a misspelt
 * field ({@code "retries"} for {@code "retry"}) has to fail the save rather than be quietly ignored
 * and run without what its author meant. The format grows by adding fields in a later
 * {@code schemaVersion}; the service keeps reading every version it knows. The extension points are
 * the maps whose keys the author chooses — {@code parameters}, and a processor's
 * {@code properties}, where a name the processor does not declare is accepted only if the processor
 * takes dynamic properties (checked by {@link FlowValidator}).
 *
 * <p>Every unknown field is reported, not just the first, each with its JSON Pointer.
 */
public final class FlowDefinitions {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** More unknown fields than this, and the definition is not worth reading further. */
    private static final int MAX_UNKNOWN = 50;

    private FlowDefinitions() {
    }

    /** @throws InvalidFlowException listing every unknown field, or the one parse error */
    public static FlowDefinition parse(String json) {
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw invalid(e);
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidFlowException(List.of(new ValidationProblem("#",
                    "A flow definition is a JSON object.")));
        }
        // Jackson stops at the first unknown field, and its path is the one reliable pointer to
        // it. So note it, take it out of the tree, and convert again, until none are left.
        List<ValidationProblem> unknown = new ArrayList<>();
        while (true) {
            try {
                FlowDefinition definition = MAPPER.treeToValue(tree, FlowDefinition.class);
                if (!unknown.isEmpty()) {
                    throw new InvalidFlowException(unknown);
                }
                return definition;
            } catch (UnrecognizedPropertyException e) {
                unknown.add(new ValidationProblem(pointer(e.getPath()),
                        "Unknown field \"" + e.getPropertyName() + "\"."));
                if (!remove(tree, e.getPath()) || unknown.size() >= MAX_UNKNOWN) {
                    throw new InvalidFlowException(unknown);
                }
            } catch (JacksonException e) {
                if (!unknown.isEmpty()) {
                    throw new InvalidFlowException(unknown);
                }
                throw invalid(e);
            }
        }
    }

    private static InvalidFlowException invalid(JacksonException e) {
        return new InvalidFlowException(List.of(new ValidationProblem(pointer(e.getPath()),
                "Not a valid flow definition: " + e.getOriginalMessage())));
    }

    /** Removes the field at {@code path} from {@code tree}; false when it is not there. */
    private static boolean remove(JsonNode tree, List<JacksonException.Reference> path) {
        JsonNode node = tree;
        for (int i = 0; i < path.size() - 1; i++) {
            JacksonException.Reference ref = path.get(i);
            node = ref.getPropertyName() != null ? node.get(ref.getPropertyName()) : node.get(ref.getIndex());
            if (node == null) {
                return false;
            }
        }
        String name = path.getLast().getPropertyName();
        return node instanceof ObjectNode object && name != null && object.remove(name) != null;
    }

    private static String pointer(List<JacksonException.Reference> path) {
        StringBuilder pointer = new StringBuilder("#");
        for (JacksonException.Reference ref : path) {
            pointer.append('/').append(ref.getPropertyName() != null
                    ? Template.escapePointer(ref.getPropertyName()) : String.valueOf(ref.getIndex()));
        }
        return pointer.toString();
    }
}
