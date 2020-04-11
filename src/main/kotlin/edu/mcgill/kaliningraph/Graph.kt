package edu.mcgill.kaliningraph

import guru.nidi.graphviz.attribute.*
import guru.nidi.graphviz.edge
import guru.nidi.graphviz.engine.Engine
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Renderer
import guru.nidi.graphviz.graph
import guru.nidi.graphviz.model.Factory.mutNode
import guru.nidi.graphviz.node
import guru.nidi.graphviz.toGraphviz
import org.ejml.data.DMatrixRMaj
import org.ejml.kotlin.minus
import java.io.File
import java.util.*

fun randomString() = UUID.randomUUID().toString()

class Node(
  val id: String = randomString(),
  val out: Set<Node> = emptySet()
) {
  val edges: Set<Edge> = out.map { Edge(this, it) }.toSet()

  fun Set<Node>.neighbors() = flatMap { it.neighbors() }.toSet()

  tailrec fun neighbors(k: Int = 0, neighbors: Set<Node> = out + this): Set<Node> =
    if (k == 0 || neighbors.neighbors() == neighbors) neighbors
    else neighbors(k - 1, neighbors + neighbors.neighbors() + this)

  fun asGraph() = Graph(neighbors(-1))

  fun egoGraph() = Graph(neighbors(0).closure())

  fun Set<Node>.closure() =
    map { Node(it.id, it.out.intersect(this@closure)) }.toSet()

  override fun toString() = id

  override fun hashCode() = id.hashCode()

  operator fun minus(node: Node) = Node(node.id, node.out + this)

  operator fun plus(node: Node) = asGraph() + node.asGraph()

  override fun equals(other: Any?) = (other as? Node)?.id == id
}

data class Edge(val source: Node, val target: Node, val id: String = randomString())

class Graph(val V: Set<Node> = emptySet()) : Set<Node> by V {
  constructor(vararg graphs: Graph) : this(graphs.fold(Graph()) { it, acc -> it + acc }.V)
  constructor(vararg nodes: Node) : this(nodes.map { it.asGraph() })
  constructor(graphs: List<Graph>) : this(*graphs.toTypedArray())
  constructor(adjList: Map<Node, Set<Node>>) : this(adjList.keys)

  val nodesById = V.map { it.id to it }.toMap()
  val numEdges = V.map { it.out.size }.sum()
  val index = NodeIndex(V)

  class NodeIndex(val set: Set<Node>) {
    val array: Array<Node> = set.toTypedArray()
    val map: Map<Node, Int> = array.mapIndexed { index, a -> a to index }.toMap()
    operator fun get(it: Node) = map[it]
    operator fun get(it: Int) = array[it]
  }

  operator fun get(node: Node) = nodesById[node.id]
  operator fun get(nodeId: String) = nodesById[nodeId]
  operator fun get(nodeIdx: Int) = index[nodeIdx]

  // Degree matrix
  val D by lazy {
    DMatrixRMaj(V.size, V.size).apply {
      V.forEachIndexed { i, node -> set(i, i, node.out.size.toDouble()) }
    }
  }

  // Adjacency matrix
  val A by lazy {
    DMatrixRMaj(V.size, V.size).apply {
      V.forEach { node ->
        node.out.forEach { neighbor ->
          set(index[node]!!, index[neighbor]!!, 1.0)
        }
      }
    }
  }

  val laplacian by lazy { D - A }

  // Implements graph merge. For all nodes in common, merge their neighbors.
  operator fun plus(that: Graph) =
    Graph((this - that) + (this intersect that) + (that - this))

  infix fun intersect(that: Graph) =
    (V intersect that.V).toSortedSet(compareBy { it.id })
      .zip((that.V intersect V).toSortedSet(compareBy { it.id }))
      .map { (left, right) -> Node(left.id, left.out + right.out) }

  operator fun minus(graph: Graph) = Graph(V - graph.V)

  fun reversed(): Graph =
    Graph(V.map { it to emptySet<Node>() }.toMap() + V.map { it to it.out }
      .flatMap { (k, v) -> v.map { it to k } }.groupBy { it.first }
      .map { (k, v) -> k to v.map { it.second }.toSet() }.toMap())

  val histogram by lazy { poolingBy { size } }

  // Weisfeiler-Lehman isomorphism test: http://www.jmlr.org/papers/volume12/shervashidze11a/shervashidze11a.pdf#page=6
  tailrec fun computeWL(k: Int = 5, labels: Map<Node, Int> = histogram): Map<Node, Int> =
    if (k <= 0) labels
    else computeWL(k - 1, poolingBy { map { labels[it]!! }.sorted().hashCode() })

  fun isomorphicTo(that: Graph) = V.size == that.V.size && numEdges == that.numEdges &&
    computeWL().values.sorted().hashCode() == that.computeWL().values.sorted().hashCode()

  fun <R> poolingBy(op: Set<Node>.() -> R): Map<Node, R> =
    V.map { it to op(it.neighbors()) }.toMap()
}

object GraphBuilder {
  val a = Node("a")
  val b = Node("b")
  val c = Node("c")
  val d = Node("d")
  val e = Node("e")
  val f = Node("f")
  val g = Node("g")
  val h = Node("h")
  val i = Node("i")
  val j = Node("j")
  val k = Node("k")
  val l = Node("l")
}

fun buildGraph(builder: GraphBuilder.() -> Graph) = builder(GraphBuilder).reversed()

val DARKMODE = false
val THICKNESS = 2

inline fun render(format: Format = Format.SVG, crossinline op: () -> Unit) =
  graph(directed = true) {
    val color = Color.BLACK

    edge[color, Arrow.NORMAL, Style.lineWidth(THICKNESS)]

    graph[Rank.dir(Rank.RankDir.LEFT_TO_RIGHT), Color.TRANSPARENT.background()]

    node[color, color.font(), Font.config("Helvetica", 20), Style.lineWidth(THICKNESS)]

    op()
  }.toGraphviz().apply { engine(Engine.NEATO) }.render(format)

fun Renderer.show() = toFile(File.createTempFile("temp", ".svg")).show()
fun Graph.show() = render {
  V.forEach { node ->
    node.out.forEach { neighbor -> mutNode(node.id).addLink(neighbor.id) }
  }
}.show()

fun File.show() = ProcessBuilder("x-www-browser", path).start()