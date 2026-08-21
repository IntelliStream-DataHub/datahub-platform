// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import ai.intellistream.datahub.jpa.domains.INode;

public class INodeProxy {

    private long id;
    private String externalId;
    private INode.INodeType nodeType;
    private String path;
    private byte[] checksum;
    private Long parentId;

    public INodeProxy() {
    }

    public INodeProxy(long id, String externalId, long nodeType, String path, byte[] checksum, Long parentId) {
        this.id = id;
        this.externalId = externalId;
        this.nodeType = INode.INodeType.values()[(int)nodeType];
        this.path = path;
        this.checksum = checksum;
        this.parentId = parentId;
    }


    public long getId() {
        return id;
    }

    public INodeProxy setId(long id) {
        this.id = id;
        return this;
    }

    public String getExternalId() {
        return externalId;
    }

    public INodeProxy setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public INode.INodeType getNodeType() {
        return nodeType;
    }

    public INodeProxy setNodeType(INode.INodeType nodeType) {
        this.nodeType = nodeType;
        return this;
    }

    public String getPath() {
        return path;
    }

    public INodeProxy setPath(String path) {
        this.path = path;
        return this;
    }

    public byte[] getChecksum() {
        return checksum;
    }

    public INodeProxy setChecksum(byte[] checksum) {
        this.checksum = checksum;
        return this;
    }

    public long getParentId() {
        return parentId;
    }

    public INodeProxy setParentId(long parentId) {
        this.parentId = parentId;
        return this;
    }
}
