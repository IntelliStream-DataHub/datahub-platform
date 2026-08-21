// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;

@Data
public abstract class DefaultDatapoint implements Datapoint{

    private Long timestamp;
    private Object value;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public DefaultDatapoint(long timestamp, Object value){
        this.timestamp = timestamp;
        this.value = value;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void setTimestamp(long timestamp){
        this.timestamp = timestamp;
    }

    @Override
    public Object getValue(){
        return value;
    }

    @Override
    public void setValue(Object value){
        this.value = value;
    }

}
