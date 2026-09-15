// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.models.policy.NamingCheckForm;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;
import java.util.Map;

/**
 * Policies — the rules a dataset is held to (naming conventions, read-only, field masking), each
 * one an instance of a policy type.
 *
 * <p>A policy is a node in the same graph as resources, which is why {@link #listTypes()} and
 * {@link #list(int)} both answer in {@link Policy}. Attach one to a dataset through the dataset's
 * own policy list rather than from here.
 */
public final class PolicyService {

    private final ApiHttp http;
    private final JavaType policies; // DataWrapper<Policy>
    private final JavaType findings; // Map<String, List<PolicyFinding>>

    public PolicyService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.policies = tf.constructParametricType(DataWrapper.class, Policy.class);
        this.findings = tf.constructMapType(java.util.LinkedHashMap.class,
                tf.constructType(String.class),
                tf.constructCollectionType(List.class, PolicyFinding.class));
    }

    /** GET /policies — the first {@code limit} policies the caller can read. */
    public DataWrapper<Policy> list(int limit) {
        return http.get("/policies?limit=" + limit, policies);
    }

    /**
     * GET /policies/types — the catalogue of policy types a policy can instantiate.
     *
     * <p>Read this before {@link #create(List)}: each entry's {@code templateId} is what a new
     * policy names to say which rule it is.
     */
    public DataWrapper<Policy> listTypes() {
        return http.get("/policies/types", policies);
    }

    /** GET /policies/{policyNodeId} — one policy by its node id. */
    public DataWrapper<Policy> getById(long policyNodeId) {
        return http.get("/policies/" + policyNodeId, policies);
    }

    /**
     * POST /policies/create — each entry needs a unique {@code name} and the {@code templateId} of
     * the type it instantiates; {@code externalId} is optional.
     */
    public DataWrapper<Policy> create(List<Policy> items) {
        return http.post("/policies/create", new DataWrapper<Policy>().setItems(items), policies);
    }

    /**
     * POST /policies/update — only the fields named in each entry's {@code update} block change.
     *
     * <p>Partial by design: the whole-object form this replaced silently re-activated a
     * switched-off policy on any unrelated edit, because {@code deactivated} was absent from the
     * body and read as false. Omitting a field now leaves it alone.
     */
    public DataWrapper<Policy> update(List<UpdatePolicyForm> updates) {
        return http.post("/policies/update", new DataWrapper<UpdatePolicyForm>().setItems(updates), policies);
    }

    /**
     * POST /policies/naming/check — dry-run a name against the naming policies, keyed by the
     * policy that has something to say about it. An empty map means every policy is satisfied.
     *
     * <p>Checks only, changes nothing: use it to tell someone their name is wrong while they are
     * still typing it, rather than failing the create.
     */
    public Map<String, List<PolicyFinding>> checkNaming(NamingCheckForm form) {
        return http.post("/policies/naming/check", form, findings);
    }

    /** DELETE /policies/delete — the endpoint answers {@code 204} with no body. */
    public void delete(List<IdCollection> ids) {
        http.send("DELETE", "/policies/delete", new DataWrapper<IdCollection>().setItems(ids));
    }
}
