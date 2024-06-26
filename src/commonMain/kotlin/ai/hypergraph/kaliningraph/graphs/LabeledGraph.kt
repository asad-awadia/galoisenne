package ai.hypergraph.kaliningraph.graphs

import ai.hypergraph.kaliningraph.*
import ai.hypergraph.kaliningraph.sampling.randomString
import ai.hypergraph.kaliningraph.tensor.BooleanMatrix
import ai.hypergraph.kaliningraph.types.*
import kotlin.reflect.KProperty

/**
 * DSL for labeled graphs - just enumerate paths. Duplicates will be merged.
 */

class LGBuilder internal constructor() {
  var mutGraph = LabeledGraph()

  val a by LGVertex(); val b by LGVertex(); val c by LGVertex()
  val d by LGVertex(); val e by LGVertex(); val f by LGVertex()
  val g by LGVertex(); val h by LGVertex(); val i by LGVertex()
  val j by LGVertex(); val k by LGVertex(); val l by LGVertex()
  val m by LGVertex(); val n by LGVertex(); val o by LGVertex()
  val p by LGVertex(); val q by LGVertex(); val r by LGVertex()
  val s by LGVertex(); val t by LGVertex(); val u by LGVertex()
  val v by LGVertex(); val w by LGVertex(); val x by LGVertex()
  val y by LGVertex(); val z by LGVertex()

  operator fun LGVertex.minus(v: LGVertex) =
    V(v) { v.outgoing + LabeledEdge(v, this) }.also { mutGraph += it.graph }
  operator fun LGVertex.minus(v: String): LGVertex = this - LGVertex(v)
  operator fun String.minus(v: LGVertex): LGVertex = LGVertex(this) - v
  operator fun String.minus(v: String): LGVertex = LGVertex(this) - LGVertex(v)
  operator fun String.set(s: String, v: String): LGVertex =
    ProtoEdge(LGVertex(this), s) - LGVertex(v)

  operator fun LGVertex.plus(edge: LabeledEdge) =
    V(this) { outgoing + edge }.also { mutGraph += it.graph }

  operator fun LGVertex.plus(vertex: LGVertex) =
    (graph + vertex.graph).also { mutGraph += it }

  class ProtoEdge(val source: LGVertex, val label: String)

  // Arithmetic is right-associative, so we construct in reverse and flip after
  operator fun ProtoEdge.minus(target: LGVertex) = target + LabeledEdge(target, source, label)
}

interface LGFamily: IGF<LabeledGraph, LabeledEdge, LGVertex> {
  override val E: (s: LGVertex, t: LGVertex) -> LabeledEdge
    get() = { s, t -> LabeledEdge(s, t) }
  override val G: (vertices: Set<LGVertex>) -> LabeledGraph
    get() = { vertices: Set<LGVertex> -> LabeledGraph(vertices) }
  override val V: (old: LGVertex, edgeMap: (LGVertex) -> Set<LabeledEdge>) -> LGVertex
    get() = { old: LGVertex, edgeMap: (LGVertex) -> Set<LabeledEdge> -> LGVertex(old, edgeMap ) }

  override fun V(out: Set<LGVertex>) = LGVertex(label = randomString(), out = out)
}

// TODO: convert to/from other graph types
open class LabeledGraph constructor(override val vertices: Set<LGVertex> = setOf()):
  Graph<LabeledGraph, LabeledEdge, LGVertex>(vertices), LGFamily {
  constructor(vararg vertices: LGVertex): this(vertices.toSet())
  constructor(builder: LGBuilder.() -> Unit):
    this(LGBuilder().also { it.builder() }.mutGraph.reversed())
  constructor(graph: String): this(
    graph.split(Regex("\\s+")).fold(LabeledGraph()) { acc, it ->
      acc + P(*it.toList().zipWithNext().map { (a, b) -> a.toString() cc b.toString() }.toTypedArray())
    }
  )
  companion object: LabeledGraph() {
    fun P(
      vararg adjList: V2<String>,
      p2v: (V2<String>) -> LGVertex = { (s, t) -> LGVertex(label=s, out=setOf(LGVertex(t))) }
    ) = LabeledGraph(adjList.map { p2v(it) }.fold(LabeledGraph()) { acc, v -> acc + v.graph })
  }

  var accumuator = mutableSetOf<String>()
  var description = ""

  override fun reversed(): LabeledGraph =
    (vertices.associateWith { setOf<LabeledEdge>() } +
        vertices.flatMap { src ->
          src.outgoing.map { edge -> edge.target to LabeledEdge(edge.target, src, edge.label) }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.toSet() })
      .map { (k, v) -> V(k) { v } }.toSet().let { G(it) }

  fun S() = BooleanMatrix(vertices.size, 1) { i, j -> this[i].occupied }

  fun rewrite(substitution: V2<String>) =
    randomWalk().take(200).toList().joinToString("")
      .replace(substitution.first, substitution.second)
      .let { LabeledGraph(it) }

  fun propagate() {
    val (previousStates, unoccupied) = vertices.partition { it.occupied }
    val nextStates = unoccupied.intersect(previousStates.flatMap { it.neighbors }.toSet())
    previousStates.forEach { it.occupied = false }
    nextStates.forEach { it.occupied = true; accumuator.add(it.id) }
  }
}

// TODO: Move occupancy, propagation and accumulator/description here
class StatefulGraph: LabeledGraph()

open class LGVertex internal constructor(
  open val label: String = "",
  override val id: String = label,
  override val edgeMap: (LGVertex) -> Set<LabeledEdge>,
): Vertex<LabeledGraph, LabeledEdge, LGVertex>(id), LGFamily {
  var occupied: Boolean = false

  constructor(
    label: String = "#RGEN_" + randomString(),
    id: String = label,
    out: Set<LGVertex> = emptySet()
  ) : this(label = label, id = id, edgeMap = { s ->
    out.map { t -> LabeledEdge(s, t, label.substringBefore("#RGEN_")) }.toSet() })

  constructor(lgv: LGVertex, edgeMap: (LGVertex) -> Set<LabeledEdge>) :
    this(label = lgv.label, id = lgv.id, edgeMap = edgeMap)

  override fun encode() = label.vectorize()
  operator fun getValue(a: Any?, prop: KProperty<*>): LGVertex = LGVertex(prop.name)

  override fun toString(): String = label
}

class FreshLGVertex internal constructor(
  override val label: String = "",
  override val id: String = randomString(),
  override val edgeMap: (LGVertex) -> Set<LabeledEdge>,
): LGVertex(label, id, edgeMap) {
  constructor(
    label: String,
    out: Set<LGVertex> = emptySet()
  ): this(
    label = label,
    edgeMap = { s -> out.map { t -> LabeledEdge(s, t) }.toSet() }
  )

  constructor(lgv: LGVertex, edgeMap: (LGVertex) -> Set<LabeledEdge>):
    this(label = lgv.label, edgeMap = edgeMap)
}

open class LabeledEdge(
  override val source: LGVertex,
  override val target: LGVertex,
  val label: String? = null
): Edge<LabeledGraph, LabeledEdge, LGVertex>(source, target), LGFamily {
  constructor(source: LGVertex, target: LGVertex): this(source, target, null)
}