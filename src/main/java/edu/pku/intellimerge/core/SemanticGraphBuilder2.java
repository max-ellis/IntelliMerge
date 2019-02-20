package edu.pku.intellimerge.core;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import edu.pku.intellimerge.model.MergeScenario;
import edu.pku.intellimerge.model.SemanticEdge;
import edu.pku.intellimerge.model.SemanticNode;
import edu.pku.intellimerge.model.constant.EdgeType;
import edu.pku.intellimerge.model.constant.NodeType;
import edu.pku.intellimerge.model.constant.Side;
import edu.pku.intellimerge.model.node.*;
import org.jgrapht.Graph;
import org.jgrapht.graph.builder.GraphTypeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Build Semantic Graph for one merge scenario, with fuzzy matching instead of symbolsolving */
public class SemanticGraphBuilder2 {
  private static final Logger logger = LoggerFactory.getLogger(SemanticGraphBuilder2.class);
  private Graph<SemanticNode, SemanticEdge> graph;
  // incremental id, unique in one side's graph
  private int nodeCount;
  private int edgeCount;
  /*
   * a series of temp containers to keep relationships between node and symbol
   * if the symbol is internal: draw the edge in graph;
   * else:
   */
  private Map<SemanticNode, List<String>> importEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> extendEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> implementEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> declObjectEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> initObjectEdges = new HashMap<>();
  private Map<SemanticNode, List<FieldAccessExpr>> readFieldEdges = new HashMap<>();
  private Map<SemanticNode, List<FieldAccessExpr>> writeFieldEdges = new HashMap<>();
  private Map<SemanticNode, List<MethodCallExpr>> callMethodEdges = new HashMap<>();

  private MergeScenario mergeScenario;
  private Side side;
  private String collectedFilePath;

  public SemanticGraphBuilder2(MergeScenario mergeScenario, Side side, String collectedFilePath) {
    this.mergeScenario = mergeScenario;
    this.side = side;
    this.collectedFilePath = collectedFilePath;
    this.graph = initGraph();
    this.nodeCount = 0;
    this.edgeCount = 0;
  }

  /**
   * Build and initialize an empty Graph
   *
   * @return
   */
  public static Graph<SemanticNode, SemanticEdge> initGraph() {
    return GraphTypeBuilder.<SemanticNode, SemanticEdge>directed()
        .allowingMultipleEdges(true)
        .allowingSelfLoops(true) // recursion
        .edgeClass(SemanticEdge.class)
        .weighted(true)
        .buildGraph();
  }

  /**
   * Build the graph by parsing the collected files
   *
   * @return
   */
  public Graph<SemanticNode, SemanticEdge> build() {

    // the folder path which contains collected files to build the graph upon
    String sideDiffPath = collectedFilePath + File.separator + side.asString() + File.separator;
    // just for sure: reinit the graph
    this.graph = initGraph();

    // parse all java files in the file
    // regular project: only one source folder
    File root = new File(sideDiffPath);
    //    SourceRoot sourceRoot = new SourceRoot(root.toPath());
    //    sourceRoot.getParserConfiguration().setSymbolResolver(symbolSolver);

    // multi-module project: separated source folder for sub-projects/modules
    ProjectRoot projectRoot = new ParserCollectionStrategy().collect(root.toPath());
    List<CompilationUnit> compilationUnits = new ArrayList<>();

    for (SourceRoot sourceRoot : projectRoot.getSourceRoots()) {
      List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParseParallelized();
      //      List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse();
      compilationUnits.addAll(
          parseResults
              .stream()
              .filter(ParseResult::isSuccessful)
              .map(r -> r.getResult().get())
              .collect(Collectors.toList()));
    }

    /*
     * build the graph by analyzing every CU
     */
    for (CompilationUnit cu : compilationUnits) {
      processCompilationUnit(cu);
    }

    // now vertices are fixed

    // build the recorded edges actually
    // TODO import can be any type, even inner type
    edgeCount = buildEdges(graph, edgeCount, importEdges, EdgeType.IMPORT, NodeType.CLASS);
    edgeCount = buildEdges(graph, edgeCount, extendEdges, EdgeType.EXTEND, NodeType.CLASS);
    edgeCount =
        buildEdges(graph, edgeCount, implementEdges, EdgeType.IMPLEMENT, NodeType.INTERFACE);
    edgeCount = buildEdges(graph, edgeCount, declObjectEdges, EdgeType.DECL_OBJECT, NodeType.CLASS);
    edgeCount = buildEdges(graph, edgeCount, initObjectEdges, EdgeType.INIT_OBJECT, NodeType.CLASS);

    //    edgeCount = buildEdges(graph, edgeCount, readFieldEdges, EdgeType.READ_FIELD,
    // NodeType.FIELD);
    //    edgeCount = buildEdges(graph, edgeCount, writeFieldEdges, EdgeType.WRITE_FIELD,
    // NodeType.FIELD);
    edgeCount = buildEdgesForMethodCall(edgeCount, callMethodEdges);

    // now edges are fixed
    // save incoming edges and outgoing edges in corresponding nodes
    for (SemanticNode node : graph.vertexSet()) {
      Set<SemanticEdge> incommingEdges = graph.incomingEdgesOf(node);
      for (SemanticEdge edge : incommingEdges) {
        if (node.incomingEdges.containsKey(edge.getEdgeType())) {
          node.incomingEdges.get(edge.getEdgeType()).add(graph.getEdgeSource(edge));
        } else {
          logger.error("Unexpected in edge:" + edge);
        }
      }
      Set<SemanticEdge> outgoingEdges = graph.outgoingEdgesOf(node);
      for (SemanticEdge edge : outgoingEdges) {
        if (node.outgoingEdges.containsKey(edge.getEdgeType())) {
          node.outgoingEdges.get(edge.getEdgeType()).add(graph.getEdgeTarget(edge));
        } else {
          logger.error("Unexpected out edge:" + edge);
        }
      }
    }

    return graph;
  }

  /**
   * Process one CompilationUnit every time
   *
   * @param cu
   */
  private void processCompilationUnit(CompilationUnit cu) {
    String fileName = cu.getStorage().map(CompilationUnit.Storage::getFileName).orElse("");
    String absolutePath =
        cu.getStorage().map(CompilationUnit.Storage::getPath).map(Path::toString).orElse("");
    String relativePath =
        absolutePath.replace(collectedFilePath + side.asString() + File.separator, "");

    // whether this file is modified: if yes, all nodes in it need to be merged (rough way)
    boolean isInChangedFile = mergeScenario.isInChangedFile(side, relativePath);

    CompilationUnitNode cuNode =
        new CompilationUnitNode(
            nodeCount++,
            isInChangedFile,
            NodeType.CU,
            fileName,
            "",
            fileName,
            cu.getComment().map(Comment::toString).orElse(""),
            fileName,
            relativePath,
            absolutePath,
            cu.getPackageDeclaration().map(PackageDeclaration::toString).orElse(""),
            cu.getImports()
                .stream()
                .map(ImportDeclaration::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new)));

    // 1. package
    String packageName = "";
    if (cu.getPackageDeclaration().isPresent()) {
      PackageDeclaration packageDeclaration = cu.getPackageDeclaration().get();
      packageName = packageDeclaration.getNameAsString();
      cuNode.setQualifiedName(packageName + "." + fileName);
      // check if the package node exists
      // if not exist, create one
      String finalPackageName = packageName;
      Optional<SemanticNode> packageDeclNodeOpt =
          graph
              .vertexSet()
              .stream()
              .filter(
                  node ->
                      node.getNodeType().equals(NodeType.PACKAGE)
                          && node.getQualifiedName().equals(finalPackageName))
              .findAny();
      if (!packageDeclNodeOpt.isPresent()) {
        PackageDeclNode packageDeclNode =
            new PackageDeclNode(
                nodeCount++,
                isInChangedFile,
                NodeType.PACKAGE,
                finalPackageName,
                packageDeclaration.getNameAsString(),
                packageDeclaration.toString().trim(),
                packageDeclaration.getComment().map(Comment::toString).orElse(""),
                finalPackageName,
                Arrays.asList(finalPackageName.split(".")));
        graph.addVertex(cuNode);
        graph.addVertex(packageDeclNode);
        graph.addEdge(
            packageDeclNode,
            cuNode,
            new SemanticEdge(edgeCount++, EdgeType.CONTAIN, packageDeclNode, cuNode));
      } else {
        graph.addVertex(cuNode);
        graph.addEdge(
            packageDeclNodeOpt.get(),
            cuNode,
            new SemanticEdge(edgeCount++, EdgeType.CONTAIN, packageDeclNodeOpt.get(), cuNode));
      }
    }
    // 2. import
    List<ImportDeclaration> importDeclarations = cu.getImports();
    List<String> importedClassNames = new ArrayList<>();
    for (ImportDeclaration importDeclaration : importDeclarations) {
      importedClassNames.add(
          importDeclaration.getNameAsString().trim().replace("import ", "").replace(";", ""));
    }

    // 3. type declaration: annotation, enum, class/interface
    // getTypes() returns top level types declared in this compilation unit
    for (TypeDeclaration td : cu.getTypes()) {
      //        td.getMembers()
      TypeDeclNode tdNode = processTypeDeclaration(td, packageName, nodeCount++, isInChangedFile);
      graph.addVertex(tdNode);

      if (td.isTopLevelType()) {
        graph.addEdge(
            cuNode, tdNode, new SemanticEdge(edgeCount++, EdgeType.DEFINE_TYPE, cuNode, tdNode));

        if (td.isClassOrInterfaceDeclaration()) {
          ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
          // extend/implement
          if (cid.getExtendedTypes().size() > 0) {
            // single extends
            String extendedType = cid.getExtendedTypes().get(0).getNameAsString();
            List<String> temp = new ArrayList<>();
            temp.add(extendedType);
            tdNode.setExtendType(extendedType);
            extendEdges.put(tdNode, temp);
          }
          if (cid.getImplementedTypes().size() > 0) {
            List<String> implementedTypes = new ArrayList<>();
            // multiple implements
            cid.getImplementedTypes()
                .forEach(
                    implementedType -> implementedTypes.add(implementedType.getNameAsString()));
            tdNode.setImplementTypes(implementedTypes);
            implementEdges.put(tdNode, implementedTypes);
          }

          // class-imports-class(es)
          importEdges.put(cuNode, importedClassNames);
        }
        processMemebers(td, tdNode, packageName, isInChangedFile);
      }
    }
  }

  /**
   * Process the type declaration itself
   *
   * @param td
   * @param packageName
   * @param nodeCount
   * @param isInChangedFile
   * @return
   */
  private TypeDeclNode processTypeDeclaration(
      TypeDeclaration td, String packageName, int nodeCount, boolean isInChangedFile) {
    String displayName = td.getNameAsString();
    String qualifiedName = packageName + "." + displayName;
    // enum/interface/inner/local class
    NodeType nodeType = NodeType.CLASS; // default
    nodeType = td.isEnumDeclaration() ? NodeType.ENUM : nodeType;
    nodeType = td.isAnnotationDeclaration() ? NodeType.ANNOTATION : nodeType;
    if (td.isClassOrInterfaceDeclaration()) {
      ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
      nodeType = cid.isInterface() ? NodeType.INTERFACE : nodeType;
      nodeType = cid.isInnerClass() ? NodeType.INNER_CLASS : nodeType;
      nodeType = cid.isLocalClassDeclaration() ? NodeType.LOCAL_CLASS : nodeType;
    }
    // TODO why the toString cannot be resolved?
    List<String> modifiers = new ArrayList<>();
    //    List<String> modifiers =
    //        td.getModifiers().stream().map(Modifier::toString).collect(Collectors.toList());
    String access = td.getAccessSpecifier().asString();
    //
    // Modifier.getAccessSpecifier(classOrInterfaceDeclaration.getModifiers()).asString();
    String originalSignature = getTypeOriginalSignature(td);

    TypeDeclNode tdNode =
        new TypeDeclNode(
            nodeCount,
            isInChangedFile,
            nodeType,
            displayName,
            qualifiedName,
            originalSignature,
            td.getComment().map(Comment::toString).orElse(""),
            access,
            modifiers,
            nodeType.asString(),
            displayName);
    return tdNode;
  }

  /**
   * Process members (child nodes that are field, constructor or method) of type declaration
   *
   * @param td
   * @param tdNode
   * @param packageName
   * @param isInChangedFile
   */
  private void processMemebers(
      TypeDeclaration td, TypeDeclNode tdNode, String packageName, boolean isInChangedFile) {
    String qualifiedTypeName = packageName + "." + td.getNameAsString();
    List<String> modifiers;
    String access, displayName, qualifiedName, originalSignature;
    // if contains nested type declaration, iterate into it
    for (Node child : td.getChildNodes()) {
      if (child instanceof TypeDeclaration) {
        TypeDeclaration childTD = (TypeDeclaration) child;
        if (childTD.isNestedType()) {
          // add edge from the parent td to the nested td
          TypeDeclNode childTDNode =
              processTypeDeclaration(childTD, packageName, nodeCount++, isInChangedFile);
          graph.addVertex(childTDNode);
          graph.addEdge(
              tdNode,
              childTDNode,
              new SemanticEdge(edgeCount++, EdgeType.DEFINE_TYPE, tdNode, childTDNode));
          // process nested td members iteratively
          processMemebers(childTD, childTDNode, packageName, isInChangedFile);
        }
      } else {
        // for other members (constructor, field, method), create the node
        // add the edge from the parent td to the member
        // 4. field
        if (child instanceof FieldDeclaration) {
          FieldDeclaration fd = (FieldDeclaration) child;
          // there can be more than one var declared in one field declaration, add one node for
          // each
          modifiers =
              fd.getModifiers().stream().map(Modifier::toString).collect(Collectors.toList());

          access = fd.getAccessSpecifier().asString();
          for (VariableDeclarator field : fd.getVariables()) {
            displayName = field.toString();
            qualifiedName = qualifiedTypeName + "." + displayName;
            originalSignature = getFieldOriginalSignature(fd);
            String body =
                field.getInitializer().isPresent()
                    ? "=" + field.getInitializer().get().toString() + ";"
                    : ";";

            FieldDeclNode fdNode =
                new FieldDeclNode(
                    nodeCount++,
                    isInChangedFile,
                    NodeType.FIELD,
                    displayName,
                    qualifiedName,
                    originalSignature,
                    fd.getComment().map(Comment::toString).orElse(""),
                    access,
                    modifiers,
                    field.getTypeAsString(),
                    field.getNameAsString(),
                    body,
                    field.getRange());
            graph.addVertex(fdNode);
            // add edge between field and class
            graph.addEdge(
                tdNode,
                fdNode,
                new SemanticEdge(edgeCount++, EdgeType.DEFINE_FIELD, tdNode, fdNode));
            // 4.1 object creation in field declaration
            List<String> declClassNames = new ArrayList<>();
            List<String> initClassNames = new ArrayList<>();
            if (field.getType().isClassOrInterfaceType()) {
              ClassOrInterfaceType type = (ClassOrInterfaceType) field.getType();
              String classUsedInFieldName = type.getNameAsString();
              if (field.getInitializer().isPresent()) {
                initClassNames.add(classUsedInFieldName);
              } else {
                declClassNames.add(classUsedInFieldName);
              }
            }
            if (declClassNames.size() > 0) {
              declObjectEdges.put(fdNode, declClassNames);
            }
            if (initClassNames.size() > 0) {
              initObjectEdges.put(fdNode, initClassNames);
            }
          }
        }

        // 5. constructor
        if (child instanceof ConstructorDeclaration) {
          ConstructorDeclaration cd = (ConstructorDeclaration) child;
          displayName = cd.getSignature().toString();
          qualifiedName = qualifiedTypeName + "." + displayName;
          ConstructorDeclNode cdNode =
              new ConstructorDeclNode(
                  nodeCount++,
                  isInChangedFile,
                  NodeType.CONSTRUCTOR,
                  displayName,
                  qualifiedName,
                  cd.getDeclarationAsString(),
                  cd.getComment().map(Comment::toString).orElse(""),
                  displayName,
                  cd.getBody().toString(),
                  cd.getRange());
          graph.addVertex(cdNode);
          graph.addEdge(
              tdNode,
              cdNode,
              new SemanticEdge(edgeCount++, EdgeType.DEFINE_CONSTRUCTOR, tdNode, cdNode));

          processMethodOrConstructorBody(cd, cdNode);
        }
        // 6. method
        if (child instanceof MethodDeclaration) {
          MethodDeclaration md = (MethodDeclaration) child;
          if (md.getAnnotations().size() > 0) {
            if (md.isAnnotationPresent("Override")) {
              // search the method signature in its superclass or interface
            }
          }
          displayName = md.getSignature().toString();
          qualifiedName = qualifiedTypeName + "." + displayName;
          modifiers =
              md.getModifiers().stream().map(Modifier::toString).collect(Collectors.toList());
          List<String> parameterTypes =
              md.getParameters()
                  .stream()
                  .map(Parameter::getType)
                  .map(Type::asString)
                  .collect(Collectors.toList());
          List<String> parameterNames =
              md.getParameters()
                  .stream()
                  .map(Parameter::getNameAsString)
                  .collect(Collectors.toList());
          access = md.getAccessSpecifier().asString();
          List<String> throwsExceptions =
              md.getThrownExceptions()
                  .stream()
                  .map(ReferenceType::toString)
                  .collect(Collectors.toList());
          MethodDeclNode mdNode =
              new MethodDeclNode(
                  nodeCount++,
                  isInChangedFile,
                  NodeType.METHOD,
                  displayName,
                  qualifiedName,
                  md.getDeclarationAsString(),
                  md.getComment().map(Comment::toString).orElse(""),
                  access,
                  modifiers,
                  md.getTypeAsString(),
                  displayName.substring(0, displayName.indexOf("(")),
                  parameterTypes,
                  parameterNames,
                  throwsExceptions,
                  md.getBody().map(BlockStmt::toString).orElse(""),
                  md.getRange());
          graph.addVertex(mdNode);
          graph.addEdge(
              tdNode,
              mdNode,
              new SemanticEdge(edgeCount++, EdgeType.DEFINE_METHOD, tdNode, mdNode));

          processMethodOrConstructorBody(md, mdNode);
        }
      }
    }
  }

  /**
   * Process interactions with other nodes inside CallableDeclaration (i.e. method or constructor)
   * body
   *
   * @param cd
   * @param node
   */
  private void processMethodOrConstructorBody(CallableDeclaration cd, TerminalNode node) {
    String displayName = "";
    String qualifiedName = "";
    // 1 new instance
    List<ObjectCreationExpr> objectCreationExprs = cd.findAll(ObjectCreationExpr.class);
    List<String> createObjectNames = new ArrayList<>();
    for (ObjectCreationExpr objectCreationExpr : objectCreationExprs) {
      String typeName = objectCreationExpr.getTypeAsString();
      createObjectNames.add(typeName);
    }
    if (createObjectNames.size() > 0) {
      initObjectEdges.put(node, createObjectNames);
    }

    // 2 field access
    // TODO support self field access
    List<FieldAccessExpr> fieldAccessExprs = cd.findAll(FieldAccessExpr.class);
    List<FieldAccessExpr> readFieldExprs = new ArrayList<>();
    List<FieldAccessExpr> writeFieldExprs = new ArrayList<>();
    for (FieldAccessExpr fieldAccessExpr : fieldAccessExprs) {
      // internal types
      // whether the field is assigned a value
      if (fieldAccessExpr.getParentNode().isPresent()) {
        Node parent = fieldAccessExpr.getParentNode().get();
        if (parent instanceof AssignExpr) {
          AssignExpr parentAssign = (AssignExpr) parent;
          if (parentAssign.getTarget().equals(fieldAccessExpr)) {
            writeFieldExprs.add(fieldAccessExpr);
          }
        }
      }
      readFieldExprs.add(fieldAccessExpr);
    }
    if (readFieldExprs.size() > 0) {
      readFieldEdges.put(node, readFieldExprs);
    }
    if (writeFieldExprs.size() > 0) {
      writeFieldEdges.put(node, writeFieldExprs);
    }
    // 3 method call
    List<MethodCallExpr> methodCallExprs = cd.findAll(MethodCallExpr.class);
    callMethodEdges.put(node, methodCallExprs);
  }

  /**
   * Get signature of field in original code
   *
   * @param fieldDeclaration
   * @return
   */
  private String getFieldOriginalSignature(FieldDeclaration fieldDeclaration) {
    String source = fieldDeclaration.toString();
    //    if (fieldDeclaration.getComment().isPresent()) {
    //      source = source.replace(fieldDeclaration.getComment().get().getContent(), "");
    //    }
    return removeComment(
            source.substring(0, (source.contains("=") ? source.indexOf("=") : source.indexOf(";"))))
        .trim();
  }

  /** Get signature of type in original code */
  private String getTypeOriginalSignature(TypeDeclaration typeDeclaration) {
    // remove comment if there is in string representation
    String source = typeDeclaration.toString();
    //    if (typeDeclaration.getComment().isPresent()) {
    //      source = source.replace(typeDeclaration.getComment().get().getContent(), "");
    //    }
    return removeComment(source.substring(0, source.indexOf("{"))).trim();
    //    return source.trim();
  }

  private String removeComment(String source) {
    return source.replaceAll("(?:/\\*(?:[^*]|(?:\\*+[^*/]))*\\*+/)|(?://.*)", "");
  }

  private int buildEdgesForMethodCall(
      int edgeCount, Map<SemanticNode, List<MethodCallExpr>> callMethodEdges) {
    // for every method call, find its declaration by method name and paramater num
    for (Map.Entry<SemanticNode, List<MethodCallExpr>> entry : callMethodEdges.entrySet()) {
      SemanticNode caller = entry.getKey();
      List<MethodCallExpr> exprs = entry.getValue();
      for (MethodCallExpr expr : exprs) {
        String displayName = expr.getNameAsString();
        int argNum = expr.getArguments().size();
        List<SemanticNode> methodNodes =
            graph
                .vertexSet()
                .stream()
                .filter(
                    node ->
                        node.getNodeType().equals(NodeType.METHOD)
                            && node.getDisplayName()
                                .substring(0, node.getDisplayName().indexOf("("))
                                .equals(displayName))
                .collect(Collectors.toList());
        for (SemanticNode node : methodNodes) {
          MethodDeclNode methodDeclNode = (MethodDeclNode) node;
          if (methodDeclNode.getParameterNames().size() == argNum) {
            boolean isSuccessful =
                graph.addEdge(
                    caller,
                    methodDeclNode,
                    new SemanticEdge(edgeCount++, EdgeType.CALL_METHOD, caller, methodDeclNode));
            if (!isSuccessful) {
              SemanticEdge edge = graph.getEdge(caller, methodDeclNode);
              if (edge != null) {
                edge.setWeight(edge.getWeight() + 1);
              }
            }
            break;
          }
        }
      }
    }
    return edgeCount;
  }

  /**
   * Add edges according to recorded temp mappings
   *
   * @param edgeCount edge id
   * @param edges recorded temp mapping from source node to qualified name of target node
   * @param edgeType edge type
   * @param targetNodeType target node type
   * @return
   */
  private int buildEdges(
      Graph<SemanticNode, SemanticEdge> semanticGraph,
      int edgeCount,
      Map<SemanticNode, List<String>> edges,
      EdgeType edgeType,
      NodeType targetNodeType) {
    if (edges.isEmpty()) {
      return edgeCount;
    }

    Set<SemanticNode> vertexSet = semanticGraph.vertexSet();
    for (Map.Entry<SemanticNode, List<String>> entry : edges.entrySet()) {
      SemanticNode sourceNode = entry.getKey();
      List<String> targetNodeNames = entry.getValue();
      for (String targeNodeName : targetNodeNames) {
        SemanticNode targetNode = null;
        if (targetNodeType.equals(NodeType.FIELD)) {
          targetNode = getTargetNodeForField(vertexSet, targeNodeName, targetNodeType);
        } else if (targetNodeType.equals(NodeType.CLASS)) {
          targetNode = getTargetNodeForType(vertexSet, targeNodeName, targetNodeType);
        } else {
          targetNode = getTargetNode(vertexSet, targeNodeName, targetNodeType);
        }
        if (targetNode != null) {
          // if the edge was added to the graph, returns true; if the edges already exists, returns
          // false
          boolean isSuccessful =
              semanticGraph.addEdge(
                  sourceNode,
                  targetNode,
                  new SemanticEdge(edgeCount++, edgeType, sourceNode, targetNode));
          if (!isSuccessful) {
            SemanticEdge edge = semanticGraph.getEdge(sourceNode, targetNode);
            edge.setWeight(edge.getWeight() + 1);
          }
        }
      }
    }
    return edgeCount;
  }

  /**
   * Get the target node from vertex set according to qualified name
   *
   * @param vertexSet
   * @param targetQualifiedName
   * @param targetNodeType
   * @return
   */
  public SemanticNode getTargetNode(
      Set<SemanticNode> vertexSet, String targetQualifiedName, NodeType targetNodeType) {
    Optional<SemanticNode> targetNodeOpt =
        vertexSet
            .stream()
            .filter(
                node ->
                    node.getNodeType().equals(targetNodeType)
                        && node.getQualifiedName().equals(targetQualifiedName))
            .findAny();
    return targetNodeOpt.orElse(null);
  }

  /**
   * Get the target node for type decl or init by fuzzy matching
   *
   * @param vertexSet
   * @param displayName
   * @param targetNodeType
   * @return
   */
  public SemanticNode getTargetNodeForType(
      Set<SemanticNode> vertexSet, String displayName, NodeType targetNodeType) {
    Optional<SemanticNode> targetNodeOpt =
        vertexSet
            .stream()
            .filter(
                node ->
                    node.getNodeType().equals(targetNodeType)
                        && node.getQualifiedName().equals(displayName))
            .findAny();
    return targetNodeOpt.orElse(null);
  }

  /**
   * Get the target node for field access by fuzzy matching
   *
   * @param vertexSet
   * @param fieldAccessString
   * @param targetNodeType
   * @return
   */
  public SemanticNode getTargetNodeForField(
      Set<SemanticNode> vertexSet, String fieldAccessString, NodeType targetNodeType) {
    // for field, match by field name
    if (fieldAccessString.contains(".")) {
      fieldAccessString =
          fieldAccessString.substring(
              fieldAccessString.lastIndexOf("."), fieldAccessString.length());
    }
    String displayName = fieldAccessString;
    // for method, match by method name and paramater num
    Optional<SemanticNode> targetNodeOpt = Optional.empty();
    if (targetNodeType.equals(NodeType.FIELD)) {

      targetNodeOpt =
          vertexSet
              .stream()
              .filter(
                  node ->
                      node.getNodeType().equals(targetNodeType)
                          && node.getDisplayName().equals(displayName))
              .findAny();
    }

    return targetNodeOpt.orElse(null);
  }

  /**
   * Setup and config the JavaSymbolSolver
   *
   * @param packagePath
   * @param libPath
   * @return
   */
  private JavaSymbolSolver setUpSymbolSolver(String packagePath, String libPath) {
    // set up the JavaSymbolSolver
    //    TypeSolver jarTypeSolver = JarTypeSolver.getJarTypeSolver(libPath);
    TypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
    TypeSolver javaParserTypeSolver = new JavaParserTypeSolver(new File(packagePath));
    reflectionTypeSolver.setParent(reflectionTypeSolver);
    CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
    combinedTypeSolver.add(reflectionTypeSolver);
    combinedTypeSolver.add(javaParserTypeSolver);
    JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
    return symbolSolver;
  }
}