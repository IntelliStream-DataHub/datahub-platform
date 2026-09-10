// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Getter @Setter
@Schema(name = "Relation", description = "Relation object")
public class EdgeProxy implements Serializable {

    @Serial
    private static final long serialVersionUID = -851555949766242009L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * The node this edge starts at. String-serialised like every other id: these are the same
     * xxHash node ids {@code Resource.id} carries, and emitting them as numbers here was the one
     * place a JavaScript client could lose precision on a node id. (Avro is unaffected — the field
     * type is unchanged; only the JSON representation moves.)
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long start;

    /** The node this edge ends at. String-serialised for the same reason as {@link #start}. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long end;

    private String type;

    private String description;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long relationshipTypeId;

    /**
     * The data set this edge belongs to. Nodes have carried theirs into the graph for a long time;
     * edges did not, so the assignment existed only in Postgres and an edge read back from the
     * graph could not say what scoped it.
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long dataSetId;

    private Map<String, String> metadata = new HashMap<>();

    public EdgeProxy(){
    }

    public EdgeProxy(long id){
        this.id = id;
    }

    public EdgeProxy(Long id, long start, long end, String type, Long relationshipTypeId, Map<String, String> metadata) {
        this.start = start;
        this.end = end;
        this.type = type;
        this.relationshipTypeId = relationshipTypeId;
        this.id = id;
        this.metadata = metadata;
    }

    public long start(){
        return this.start;
    }

    public long end(){
        return this.end;
    }

    public String type(){
        return this.type;
    }

    public Map<String, String> metadata(){
        return this.metadata;
    }

    public EdgeProxy setStart(long start) {
        this.start = start;
        return this;
    }

    public EdgeProxy setEnd(long end) {
        this.end = end;
        return this;
    }

    public EdgeProxy setType(String type) {
        this.type = type;
        return this;
    }

    public EdgeProxy setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
        return this;
    }

    public EdgeProxy setId(long id) {
        this.id = id;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EdgeProxy assetEdge = (EdgeProxy) o;
        return start.equals(assetEdge.start) && end.equals(assetEdge.end) && type.equals(assetEdge.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, type);
    }
}
