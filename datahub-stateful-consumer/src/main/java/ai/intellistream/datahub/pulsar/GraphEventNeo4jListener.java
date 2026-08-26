// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.Relationship;
import org.neo4j.cypherdsl.core.Statement;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.neo4j.cypherdsl.core.Cypher.literalOf;
import static org.neo4j.driver.Values.parameters;

@Component
@Slf4j
@AllArgsConstructor
public class GraphEventNeo4jListener {

    private final Neo4j neo4j;

    /**
     * Apply one resource-CUD message to the graph. Throws on failure so the {@link PulsarReceiveLoop}
     * retries it in place (keeping the ordered stream head) rather than acking a half-applied change.
     */
    public void process(ResourceCudMessage message) {
        switch (message.getEventObject()){
            case RESOURCE_AND_RELATION, TIMESERIES, FUNCTION -> {
                switch (message.getEventAction()){
                    case CREATE -> createResourceAndRelations(message);
                    case UPDATE -> updateResourceAndRelations(message);
                    case DELETE -> deleteResourcesAndRelations(message);
                }
            }
            case LABEL -> {
                switch (message.getEventAction()){
                    case RENAME -> renameLabel(message);
                }
            }
        }
    }

    private void deleteResourcesAndRelations(ResourceCudMessage message) {
        try (Session session = neo4j.getSession(message.getTenantId());
             Transaction tx = session.beginTransaction()) {

            try{
                for (EdgeProxy ep : message.getEdges()) {
                    deleteRelation(ep, tx);
                }
                deleteResources(message.getResources(), tx);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw new RuntimeException(e);
            }

        }  catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private void updateResourceAndRelations(ResourceCudMessage message) {
        try (Session session = neo4j.getSession(message.getTenantId());
             Transaction tx = session.beginTransaction()) {
            try{
                // The labels Postgres actually ended up with, keyed by node id. The api sends the
                // post-update resources alongside the forms, so the authority travels with the
                // message and the consumer no longer has to work them out for itself.
                Map<Long, List<String>> resolvedLabels = resolvedLabelsFrom(message);
                for(UpdateResourceForm a : message.getUpdateResourceForms()){
                    updateResource(a, tx, message.getTenantId(), resolvedLabels.get(a.getId()));
                }
                for(UpdateTimeseries a : message.getUpdateTimeseries()){
                    updateResource(a, tx);
                }
                for(EdgeProxy r : message.getEdges()){
                    updateRelation(r, tx);
                }
                tx.commit();
            } catch (Exception e){
                tx.rollback();
                throw new RuntimeException(e);
            }

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private void createResourceAndRelations(ResourceCudMessage message) {
        try (Session session = neo4j.getSession(message.getTenantId());
             Transaction tx = session.beginTransaction()) {
            try{
                for(Resource a : message.getResources()){
                    createResource(a, tx);
                }
                for(EdgeProxy r : message.getEdges()){
                    createNewRelation(r, tx);
                }
                tx.commit();
            } catch (Exception e){
                tx.rollback();
                throw new RuntimeException(e);
            }

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private void deleteRelation(EdgeProxy edge, Transaction tx) {
        /*String query = """
             MATCH (n)-[r{id: $id}]->(m)
             CALL{
                 with n
                 match (n)-[r2]-()
                 with count(r2) = 1 as count1
                 RETURN count1
             }
             CALL{
                 with m
                 match (m)-[r2]-()
                 with count(r2) = 1 as count2
                 RETURN count2
             }
             DELETE r
             RETURN count1, count2, n as fromNode, m as toNode
        """;*/

        String query = """
             MATCH (n)-[r{id: $id}]->(m)
             DELETE r
        """;
        var result = tx.run(query, parameters("id", edge.getId()));
        log.debug("{}, {}", result.consume().query().text(), result.consume().query().parameters() );
    }

    private void updateRelation(EdgeProxy edge, Transaction tx) {
        /*var r = tx.run(
                "MATCH ()-[oldR{id: $rId}]->() " +
                        "MATCH (n{id: $startId}) " +
                        "MATCH (m{id: $endId}) " +
                        "CREATE (n)-[newR:`"+edge.type()+"`{id: $rId, description: $desc, start: start, end: $end, typeId: $typeId}]->(m)" +
                        "DELETE oldR ",
                parameters(kv)
        );
        ResultSummary summary = r.consume();
        log.debug("{}, {}", summary.query().text(), summary.query().parameters() );*/
        Relationship oldR = Cypher.anyNode().relationshipTo(Cypher.anyNode()).named("oldR").withProperties("id", literalOf(edge.getId()));
        Node startNode = Cypher.anyNode()
                .named("startNode")
                .withProperties("id", literalOf(edge.getStart()));
        Node endNode = Cypher.anyNode()
                .named("endNode")
                .withProperties("id", literalOf(edge.getEnd()));
        Relationship newR = startNode.relationshipTo(endNode, edge.getType())
                .named("newR")
                .withProperties( createProperties(edge) );
        var s = Cypher
                .match(oldR)
                .match(startNode)
                .match(endNode)
                .create(newR)
                .delete(oldR)
                .build();

        String cypherQuery = s.getCypher();
        log.debug("cypher: {}", cypherQuery);
        Map<String, Object> parameters = s.getCatalog().getParameters();
        // Execute the query using the Neo4j driver's transaction object
        var results = tx.run(cypherQuery, parameters);
        log.debug("result: {}", results);
    }

    /**
     * The resolved label set per node id, from the post-update resources the api sends alongside
     * the update forms. Empty when the message carries none — see
     * {@link #updateResource(UpdateResourceForm, Transaction, String, List)} on why that is
     * tolerated rather than treated as an error.
     */
    static Map<Long, List<String>> resolvedLabelsFrom(ResourceCudMessage message) {
        if (message.getResources() == null || message.getResources().isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> byId = new HashMap<>();
        for (Resource resource : message.getResources()) {
            if (resource.getId() != null && resource.getLabels() != null) {
                byId.put(resource.getId(), resource.getLabels());
            }
        }
        return byId;
    }

    private void updateResource(UpdateResourceForm resource, Transaction tx, String tenantId,
                                List<String> resolvedLabels){
        org.neo4j.driver.types.Node node = get(resource.getId(), tenantId);
        if (node == null) {
            // The node was deleted between commit and this listener running (or the CREATE
            // message hasn't arrived yet). Skipping is safe — if a CREATE follows, it will
            // carry the final state; if a DELETE follows, there's nothing to update anyway.
            log.warn("Skipping Neo4j update for resource id={} — node not found.", resource.getId());
            return;
        }
        List<String> newLabelList;
        if (resolvedLabels != null) {
            // Apply what Postgres resolved rather than re-deriving it. Re-deriving was a fourth
            // implementation of the label rules and the only one that did not enforce the
            // type-label: a `set` that omitted ASSET stripped it from the graph while Postgres
            // kept it, leaving the two stores disagreeing about what kind of node this is.
            newLabelList = new ArrayList<>(resolvedLabels);
        } else {
            // No resolved labels in the message: an api from before they were sent. Kept so a
            // rolling deploy can put the consumers out first, and removable once no such producer
            // is left.
            var labels = resource.getUpdate().getLabels();
            List<String> currentLabels = new ArrayList<>();
            node.labels().forEach(currentLabels::add);
            newLabelList = computeNewLabels(
                    currentLabels, labels.getSet(), labels.getAdd(), labels.getRemove());
        }
        var updatingResource = Cypher.anyNode()
                .named("n")
                .withProperties("id", literalOf(resource.getId()));

        var labelsToRemove = new ArrayList<String>();
        node.labels().forEach(labelsToRemove::add);
        labelsToRemove.removeAll(newLabelList);

        var ongoingStatement = Cypher.match(updatingResource).set(updatingResource, newLabelList);
        if ( !labelsToRemove.isEmpty()) {
            ongoingStatement = ongoingStatement.remove(updatingResource, labelsToRemove);
        }
        Map<String, Object> map = updateProperties(resource);
        ongoingStatement = ongoingStatement.mutate(updatingResource, Cypher.asExpression(map));
        Statement s = ongoingStatement.returning(updatingResource).build();

        String cypherQuery = s.getCypher();
        log.debug("cypher: {}", cypherQuery);
        Map<String, Object> parameters = s.getCatalog().getParameters();
        // Execute the query using the Neo4j driver's transaction object
        var results = tx.run(cypherQuery, parameters);
        log.debug("result: {}", results);
    }

    /**
     * The label set a resource update should end with: the {@code set} list when provided (a full
     * replacement), otherwise the node's current labels, then with {@code add} appended and
     * {@code remove} removed. When {@code set}, {@code add} and {@code remove} are all absent the
     * current labels are kept unchanged.
     *
     * <p>An EMPTY {@code set} list is treated as absent, mirroring the API side
     * (ResourceService guards {@code !getSet().isEmpty()} and keeps the Postgres labels) — treating
     * it as a full replacement here would strip every Neo4j label while Postgres keeps them,
     * silently diverging the graph.
     */
    static List<String> computeNewLabels(List<String> current, Collection<String> set,
                                         Collection<String> add, Collection<String> remove) {
        if (set == null && add == null && remove == null) {
            return new ArrayList<>(current);
        }
        List<String> result = new ArrayList<>(set != null && !set.isEmpty() ? set : current);
        if (add != null) {
            for (String label : add) {
                if (!result.contains(label)) {
                    result.add(label);
                }
            }
        }
        if (remove != null) {
            result.removeAll(remove);
        }
        return result;
    }

    private void updateResource(UpdateTimeseries updateForm, Transaction tx){
        var updatingResource = Cypher.anyNode()
                .named("n")
                .withProperties("id", literalOf(updateForm.getId()));

        Map<String, Object> map = updateProperties(updateForm);
        var ongoingStatement =
                Cypher.match(updatingResource)
                    .mutate(updatingResource, Cypher.asExpression(map));
        Statement s = ongoingStatement.returning(updatingResource).build();

        String cypherQuery = s.getCypher();
        log.debug("cypher: {}", cypherQuery);
        Map<String, Object> parameters = s.getCatalog().getParameters();
        // Execute the query using the Neo4j driver's transaction object
        var results = tx.run(cypherQuery, parameters);
        log.debug("result: {}", results);
    }

    private void createNewRelation(EdgeProxy edge, Transaction tx) {

        if(edge.getId() == null){
            throw new RuntimeException("Missing Datahub id!");
        }

        Node startNode =
                Cypher.anyNode().named("startNode")
                .withProperties(Map.of("id", literalOf(edge.getStart())));
        Node endNode =
                Cypher.anyNode().named("endNode")
                        .withProperties(Map.of("id", literalOf(edge.getEnd())));
        // MERGE the relationship on its stable id (not CREATE) so a redelivered/retried CREATE matches
        // the existing edge instead of duplicating it; properties are then applied with SET.
        Relationship rel = startNode.relationshipTo(endNode, edge.getType())
                .named("r")
                .withProperties("id", literalOf(edge.getId()));
        Statement q = Cypher.match(startNode).match(endNode)
                .merge(rel)
                .mutate(rel, Cypher.asExpression(createProperties(edge)))
                .build();
        // Get the query string and parameters directly from the Statement
        String cypherQuery = q.getCypher();
        Map<String, Object> parameters = q.getCatalog().getParameters();
        log.debug("cypher: {}", cypherQuery);
        // Execute the query using the Neo4j driver's transaction object
        var result = tx.run(cypherQuery, parameters);
        log.debug("result: {}", result);

    }

    private void createResource(Resource resource, Transaction tx) {
        String[] labelList = getLabelList(resource);
        String primaryLabel = labelList[0];
        if(primaryLabel == null || primaryLabel.isBlank() ){
            log.warn("Resource omitted, missing primary label.");
            log.warn("Resource: " + resource);
            return;
        }

        Map<String, Object> map = createProperties(resource);
        // MERGE on the stable id (not CREATE) so a redelivered CREATE — ackTimeout, reconnect, or an
        // in-place retry after a partial failure — matches the existing node instead of duplicating
        // it. Labels and properties are then applied with SET so the match key stays id-only.
        List<String> allLabels = Arrays.asList(labelList);
        Node mergeKey = Cypher.anyNode().named("m").withProperties("id", literalOf(resource.getId()));

        Statement s = Cypher.merge(mergeKey)
                .set(mergeKey, allLabels)
                .mutate(mergeKey, Cypher.asExpression(map))
                .build();
        // Get the query string and parameters directly from the Statement
        String cypherQuery = s.getCypher();
        Map<String, Object> parameters = s.getCatalog().getParameters();
        log.debug("cypher: {}", cypherQuery);
        // Execute the query using the Neo4j driver's transaction object
        var result = tx.run(cypherQuery, parameters);
        log.debug("result: {}", result);
    }

    private static String[] getLabelList(Resource resource) {
        String labels = String.join(":", resource.getLabels());
        final String cleanLabels = labels.replaceAll("-", "");
        return cleanLabels.split(":");
    }

    private static Map<String, Object> createProperties(Resource resource) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", resource.getId());
        map.put("externalId", resource.getExternalId());
        map.put("name", resource.getName());
        map.put("description", resource.getDescription());
        map.put("dataSetId", resource.getDataSetId());
        map.put("source", resource.getSource());
        map.put("isRoot", resource.getIsRoot());

        // Saved as DateTime in Neo4J, do not save this a string
        map.put("createdTime", resource.getCreatedTime());
        map.put("lastUpdatedTime", resource.getLastUpdatedTime());
        if (resource.getMetadata() != null) {
            for(String key : resource.getMetadata().keySet()){
                map.put("metadata_" + key, resource.getMetadata().get(key));
            }
        }
        Object geoPoint = pointExpressionOrNull(resource.getGeoLocation());
        if (geoPoint != null) {
            map.put("geoLocation", geoPoint);
        }
        return map;
    }

    /**
     * A native WGS-84 (SRID 4326) graph point for a {@code Point} geolocation, so
     * {@code point.distance(...)} returns geodesic metres — or {@code null} for a missing / non-point
     * geometry. Postgres retains the full GeoJSON; the graph carries only a point for distance
     * queries, so non-point geometries (which would need a centroid) are deferred until JTS lands.
     */
    private static Object pointExpressionOrNull(GeoLocation geo) {
        if (geo == null) {
            return null;
        }
        double[] coords = geo.pointCoordinates();
        if (coords == null) {
            log.info("Skipping graph geolocation for non-point geometry '{}'", geo.geometryType());
            return null;
        }
        return Cypher.point(Cypher.mapOf(
                "srid", literalOf(4326),
                "x", literalOf(coords[0]),
                "y", literalOf(coords[1])));
    }

    private static Map<String, Object> updateProperties(UpdateResourceForm resource) {
        var updateForm = resource.getUpdate();
        Map<String, Object> map = new HashMap<>();
        map.put("id", resource.getId());
        map.put("externalId", resource.getExternalId());
        if(updateForm.getName().getSet() != null){
            map.put("name", updateForm.getName().getSet());
        }

        if(updateForm.getDescription().getSet() != null){
            map.put("description", updateForm.getDescription().getSet());
        }
        if(updateForm.getDescription().getSetNull()){
            map.put("description", null);
        }

        if(updateForm.getDataSetId().getSet() != null){
            map.put("dataSetId", updateForm.getDataSetId().getSet());
        }
        if(updateForm.getDataSetId().getSetNull()){
            map.put("dataSetId", null);
        }

        if(updateForm.getSource().getSet() != null){
            map.put("source", updateForm.getSource().getSet());
        }
        if(updateForm.getSource().getSetNull()){
            map.put("source", null);
        }

        if(updateForm.getGeoLocation().getSet() != null){
            // Set the new point, or clear a stale one when the new geometry isn't a Point
            // (the graph holds points only; Postgres keeps the full GeoJSON either way).
            map.put("geoLocation", pointExpressionOrNull(updateForm.getGeoLocation().getSet()));
        }
        if(updateForm.getGeoLocation().getSetNull()){
            map.put("geoLocation", null);
        }

        /*if(updateForm.get().getSet() != null){
            map.put("isRoot", updateForm.getSource().getSet());
        }
        if(updateForm.getSource().getSetNull()){
            map.put("isRoot", null);
        }*/

        var lastUpdated = ZonedDateTime.now()
                .toInstant()
                .atZone(ZoneId.of("UTC"));
        map.put("lastUpdatedTime", lastUpdated);
        if (updateForm.getMetadata().getSet() != null) {
            updateForm.getMetadata().getSet().forEach((key, value) -> {
                map.putIfAbsent("metadata_" + key, value);
            });
        }
        if (updateForm.getMetadata().getAdd() != null) {
            updateForm.getMetadata().getAdd().forEach((key, value) -> {
                map.put("metadata_" + key, value);
            });
        }

        if (updateForm.getMetadata().getRemove() != null) {
            updateForm.getMetadata().getRemove().forEach(key -> {
                map.remove("metadata_" + key);
            });
        }

        return map;
    }

    private static Map<String, Object> updateProperties(UpdateTimeseries resource) {
        var updateForm = resource.getUpdate();
        Map<String, Object> map = new HashMap<>();
        map.put("id", resource.getId());
        map.put("externalId", resource.getExternalId());
        if(updateForm.getName().getSet() != null){
            map.put("name", updateForm.getName().getSet());
        }

        if(updateForm.getDescription().getSet() != null){
            map.put("description", updateForm.getDescription().getSet());
        }
        if(updateForm.getDescription().getSetNull()){
            map.put("description", null);
        }

        if(updateForm.getDataSetId().getSet() != null){
            map.put("dataSetId", updateForm.getDataSetId().getSet());
        }
        if(updateForm.getDataSetId().getSetNull()){
            map.put("dataSetId", null);
        }

        if(updateForm.getSource().getSet() != null){
            map.put("source", updateForm.getSource().getSet());
        }
        if(updateForm.getSource().getSetNull()){
            map.put("source", null);
        }

        /*if(updateForm.get().getSet() != null){
            map.put("isRoot", updateForm.getSource().getSet());
        }
        if(updateForm.getSource().getSetNull()){
            map.put("isRoot", null);
        }*/

        var lastUpdated = ZonedDateTime.now()
                .toInstant()
                .atZone(ZoneId.of("UTC"));
        map.put("lastUpdatedTime", lastUpdated);

        if (updateForm.getMetadata().getSet() != null) {
            updateForm.getMetadata().getSet().forEach((key, value) -> {
                map.putIfAbsent("metadata_" + key, value);
            });
        }
        if (updateForm.getMetadata().getAdd() != null) {
            updateForm.getMetadata().getAdd().forEach((key, value) -> {
                map.put("metadata_" + key, value);
            });
        }

        if (updateForm.getMetadata().getRemove() != null) {
            updateForm.getMetadata().getRemove().forEach(key -> {
                map.remove("metadata_" + key);
            });
        }
        return map;
    }

    private static Map<String, Object> createProperties(EdgeProxy edge) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", edge.getId());
        map.put("start", edge.getStart());
        map.put("end", edge.getEnd());
        map.put("typeId", edge.getRelationshipTypeId());
        map.put("description", edge.getDescription());

        for(String key : edge.getMetadata().keySet()){
            map.put("metadata_" + key, edge.getMetadata().get(key));
        }
        return map;
    }

    private void deleteResources(Collection<Resource> resources, Transaction tx) {
        for(Resource resource : resources){

            var deleteQuery = Cypher.anyNode().named("n");
            if(resource.getId() != null){
                Long id = resource.getId();
                deleteQuery = deleteQuery.withProperties("id", literalOf(id));
            } else if(resource.getExternalId() != null){
                String externalId = resource.getExternalId();
                deleteQuery = deleteQuery.withProperties("externalId", literalOf(externalId));
            } else {
                log.warn("Missing both id and externalId for resource: " + resource.toString());
                return;
            }

            // If you use the DETACH keyword, Neo4j removes the relationships first, then deletes the node.
            Statement s = Cypher.match(deleteQuery).detachDelete(deleteQuery).build();
            // Get the query string and parameters directly from the Statement
            String cypherQuery = s.getCypher();
            Map<String, Object> parameters = s.getCatalog().getParameters();
            log.debug("cypher: {}", cypherQuery);
            // Execute the query using the Neo4j driver's transaction object
            var result = tx.run(cypherQuery, parameters);
            log.debug("result: {}", result);
        }
    }

    public org.neo4j.driver.types.Node get(Serializable id, String tenantId){
        try (Session session = neo4j.getSession(tenantId)) {
            Node n = Cypher.anyNode()
                    .named("n")
                    .withProperties(
                            "id", literalOf(id)
                    );
            Statement statement = Cypher.match(n).returning(n).build();

            String cypherQuery = statement.getCypher();
            Map<String, Object> parameters = statement.getCatalog().getParameters();

            // Returns null when the node is absent instead of throwing, so an update that
            // races with a concurrent delete can be handled as a no-op rather than a
            // poison message that blocks the consumer.
            return session.executeRead(tx -> {
                Result result = tx.run(cypherQuery, parameters);
                return result.hasNext() ? result.next().get("n").asNode() : null;
            });
        }
    }

    private void renameLabel(ResourceCudMessage message) throws RuntimeException {
        NodeChangeMessage changeMessage = message.getChangeMessage();
        final String fromLabelName = changeMessage.getFromText();
        final String toLabelName = changeMessage.getToText();

        if(fromLabelName.equals("ASSET") || toLabelName.equals("ASSET")) return;
        if(fromLabelName.equals("TIMESERIES") || toLabelName.equals("TIMESERIES")) return;
        if(fromLabelName.equals("FUNCTION") || toLabelName.equals("FUNCTION")) return;

        try (Session session = neo4j.getSession(message.getTenantId())) {
            var m = Cypher.node(fromLabelName)
                    .named("m");
            var s = Cypher.match(m)
                    .remove(m, fromLabelName)
                    .set(m, toLabelName)
                    .build();

            String cypherQuery = s.getCypher();
            Map<String, Object> parameters = s.getCatalog().getParameters();
            log.debug("cypher: {}", cypherQuery);

            // 3. Execute the query using the driver's transaction function
            session.executeWrite(tx -> {
                tx.run(cypherQuery, parameters);
                return null;
            });
        }
    }

    /**
     * Neo4J Query
     * match (n1)-[old:`Start`]->(n2)
     * create (n1)-[new:`Start`]->(n2)
     * delete old
     */
    private void renameRelation(ResourceCudMessage message) throws RuntimeException {
        NodeChangeMessage changeMessage = message.getChangeMessage();
        try (Session session = neo4j.getSession(message.getTenantId())) {
            var m = Cypher.anyNode();
            var n = Cypher.anyNode();
            var oldRelation = m.relationshipTo(n, changeMessage.getFromText());
            var newRelation = m.relationshipTo(n, changeMessage.getToText());

            var s = Cypher.match(oldRelation)
                    .create(newRelation)
                    .delete(oldRelation)
                    .build();
            String cypherQuery = s.getCypher();
            Map<String, Object> parameters = s.getCatalog().getParameters();
            log.debug("cypher: {}", cypherQuery);

            session.executeWrite(tx -> {
                tx.run(cypherQuery, parameters);
                return null;
            });
        }
    }
}
