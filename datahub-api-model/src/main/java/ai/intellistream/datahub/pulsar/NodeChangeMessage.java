// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.pulsar;

public class NodeChangeMessage {

    private String fromText;
    private String toText;

    public NodeChangeMessage(){

    }

    public NodeChangeMessage(
            String fromText,
            String toText
    ){
        this.fromText = fromText;
        this.toText = toText;
    }

    public String getFromText() {
        return fromText;
    }

    public String getToText() {
        return toText;
    }
}
