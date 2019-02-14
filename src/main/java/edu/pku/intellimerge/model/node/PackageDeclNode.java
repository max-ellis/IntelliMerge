package edu.pku.intellimerge.model.node;

import edu.pku.intellimerge.model.constant.EdgeType;
import edu.pku.intellimerge.model.constant.NodeType;

import java.util.ArrayList;
import java.util.List;

public class PackageDeclNode extends NonTerminalNode {
  private String packageName;
  private List<String> packageNameHierachy; // qualified_package_name.split(".").remove(";")

  public PackageDeclNode(
      Integer nodeID,
      Boolean needToMerge,
      NodeType nodeType,
      String displayName,
      String qualifiedName,
      String originalSignature,
      String comment,
      String packageName,
      List<String> packageNameHierachy) {
    super(nodeID, needToMerge, nodeType, displayName, qualifiedName, originalSignature, comment);
    this.packageName = packageName;
    this.packageNameHierachy = packageNameHierachy;
    this.incomingEdges.put(EdgeType.CONTAIN, new ArrayList<>());
    this.outgoingEdges.put(EdgeType.CONTAIN, new ArrayList<>());
  }
}