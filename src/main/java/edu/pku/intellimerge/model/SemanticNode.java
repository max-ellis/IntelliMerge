package edu.pku.intellimerge.model;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

public class SemanticNode {
    private Integer nodeID;
    private Integer nodeType;
    private String displayName;
    private String qualifiedName;
    private String content;
    private Range range;
    private Node astNode;

    public SemanticNode(Integer nodeID, Integer nodeType, String displayName, String qualifiedName, String content) {
        this.nodeID = nodeID;
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.qualifiedName = qualifiedName;
        this.content = content;
    }

    public Integer getNodeID() {
        return nodeID;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    @Override

    public String toString() {
        return "SemanticNode{" +
                "nodeID=" + nodeID +
                ", nodeType='" + nodeType + '\'' +
                ", displayName='" + displayName + '\'' +
                ", qualifiedName='" + qualifiedName + '\'' +
                ", range=" + range +
                '}';
    }
}

