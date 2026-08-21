# Neo4j for the local dev stack, with APOC baked in (pinned, no auto-download).
#
# datahub-api's Neo4JService calls apoc.path.subgraphAll, so without APOC the
# resource/dataset graph view fails with "apoc.path.subgraphAll() was not
# registered". We install it by dropping the specific apoc core jar into
# $NEO4J_HOME/plugins and allowlisting apoc.path.* — no auto-download, no
# unrestricted. This image bakes that exact jar in so the install is pinned and
# reproducible (neo4j 5.26.26 + apoc-5.26.27-core.jar).
#
# The allowlist/unrestricted config lives on the service in docker-compose.yml.
FROM docker.io/library/neo4j:5.26.26

ADD https://github.com/neo4j/apoc/releases/download/5.26.27/apoc-5.26.27-core.jar \
    /var/lib/neo4j/plugins/apoc-5.26.27-core.jar
RUN chmod 644 /var/lib/neo4j/plugins/apoc-5.26.27-core.jar
