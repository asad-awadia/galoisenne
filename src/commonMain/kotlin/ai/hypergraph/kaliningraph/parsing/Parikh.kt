package ai.hypergraph.kaliningraph.parsing

import ai.hypergraph.kaliningraph.repair.*
import ai.hypergraph.kaliningraph.tokenizeByWhitespace
import ai.hypergraph.kaliningraph.types.cache
import kotlin.jvm.JvmName
import kotlin.math.*

// Number of each terminal (necessary, possible)
typealias ParikhBounds = Map<Σᐩ, IntRange>
typealias ParikhVector = Map<Σᐩ, Int>
typealias ParikhBoundsMap = Map<Σᐩ, ParikhBounds> // V -> Σ -> IntRange

fun Σᐩ.parikhVector(): ParikhVector = tokenizeByWhitespace().parikhVector()
fun List<Σᐩ>.parikhVector(): ParikhVector = groupingBy { it }.eachCount()

fun dist(pv: ParikhVector, pb: ParikhBounds) =
  (pv.keys + pb.keys).sumOf { k ->
    val bounds = (pb[k] ?: 0..0)
    val v = pv[k] ?: 0
    if (v < bounds.first) bounds.first - v
    else if (bounds.last < v) v - bounds.last
    else 0
  }

// Too slow:
//fun ParikhBounds.admits(pv: ParikhVector, margin: Int = 0) =
//  dist(pv, this) <= margin
// Like above, but short circuits if sum > margin
fun ParikhBounds.admits(pv: ParikhVector, margin: Int = 0): Boolean {
  var sum = 0
  for ((k, v) in pv) {
    val bounds = (this[k] ?: 0..0)
    if (v < bounds.first) sum += bounds.first - v
    else if (bounds.last < v) sum += v - bounds.last
    if (sum > margin) return false
  }
  return true
}

fun ParikhBounds.subsumes(pv: ParikhVector) = dist(pv, this) == 0

fun CFG.parikhBounds(nt: Σᐩ, size: Int): ParikhBounds {
  val bounds = mutableMapOf<Σᐩ, IntRange>()
  nonterminalProductions.forEach { (A, rhs) ->
    rhs.forEach { σ ->
      bounds[σ] = bounds[σ]?.let { it.first..it.last + 1 } ?: 0..1
    }
  }
  return bounds
}

// For a description of this datastructure: https://github.com/breandan/galoisenne/blob/master/latex/popl2025/rebuttal.md
class ParikhMap(val cfg: CFG, val size: Int, reconstruct: Boolean = true) {
  private val lengthBounds: MutableMap<Int, Set<Σᐩ>> = mutableMapOf()
  private val parikhMap: MutableMap<Int, ParikhBoundsMap> = mutableMapOf()
  val parikhRangeMap: MutableMap<IntRange, ParikhBoundsMap> = mutableMapOf() // Parameterized Parikh map
  val ntIdx = cfg.nonterminals.toList()

  companion object {
    fun serialize(pm: ParikhMap): String =
      serializePM(pm.parikhMap) + "\n\n====\n\n" +
          pm.lengthBounds.entries.joinToString("\n") { (k, v) -> "$k ${v.joinToString(" ")}" }

    fun serializePM(pm: Map<Int, ParikhBoundsMap>) =
      pm.entries.joinToString("\n") { (k0, v0) ->
        v0.entries.joinToString("\n") { (k1, v1) ->
          v1.entries.joinToString("\n") { (k2, v2) ->
            "$k0 $k1 $k2 ${v2.first} ${v2.last}"
          }
        }
      }

    fun deserializePM(str: String): Map<Int, ParikhBoundsMap> =
      str.lines().filter { it.isNotBlank() }
        .map { it.split(" ") }.groupBy { it[0].toInt() }.mapValues { (_, v0) ->
        v0.groupBy { it[1] }.mapValues { (_, v1) ->
          v1.map { it[2] to it[3].toInt()..it[4].toInt() }.toMap()
        }
      }.mapValues { (_, v0) ->
        v0.mapValues { (_, v1) ->
          v1.mapValues { (_, v2) ->
            v2
          }
        }
      }

    fun deserialize(cfg: CFG, str: String): ParikhMap {
      val pm = deserializePM(str.substringBefore("\n\n====\n\n"))
      val lb = str.substringAfter("\n\n====\n\n").lines().map { it.split(" ") }
        .associate { it.first().toInt() to it.drop(1).toSet() }
      println("Deserialized Parikh Map with ${pm.size} lengths and ${lb.size} bounds")
      return ParikhMap(cfg, pm.size, false).apply {
          parikhMap.putAll(pm)
          lengthBounds.putAll(lb)
          populatePRMFromPM()
      }
    }

    fun genRanges(delta: Int = 2 * MAX_RADIUS + 1, n: Int = MAX_TOKENS + MAX_RADIUS) =
      (1..delta).map { margin ->
        val range = (0..n).toList()
        range.windowed(margin, 1).map {
          it.first()..it.last()
        }
      }.flatten()
  }

  fun populatePRMFromPM() {
    genRanges(n = size).forEach { range ->
      range.map { parikhMap[it] ?: emptyMap() }
        .fold(emptyMap<Σᐩ, ParikhBounds>()) { acc, map -> pbmplus(acc, map) }
        .also {
//          println("Generating Parikh range for $range");
          parikhRangeMap[range] = it
        }
    }
  }

  init {
    if (reconstruct) {
      val template = List(size) { "_" }
      cfg.initPForestMat(template).seekFixpoint().diagonals
        .forEachIndexed { i, it ->
          println("Computing PM length $i/$size with ${it.size} keys")
          lengthBounds[i + 1] = it.first().keys
          parikhMap[i + 1] = it.first().mapValues { it.value.parikhBounds }
        }

      populatePRMFromPM()
    }
  }

  fun parikhBounds(nt: Int, range: IntRange): ParikhBounds = parikhBounds(ntIdx[nt], range)
  fun parikhBounds(nt: Σᐩ, range: IntRange): ParikhBounds = parikhRangeMap[range]?.get(nt) ?: emptyMap()
  fun parikhBounds(nt: Σᐩ, size: Int): ParikhBounds? = parikhMap[size]?.get(nt)
//    parikhMap.also { println("Keys (${nt}): " + it.keys.size + ", ${it[size]?.get(nt)}") }[size]?.get(nt)

  override fun toString(): String =
    (parikhMap.size..parikhMap.size).joinToString("\n") { i ->
      "\n\nLength $i:\n\n${parikhMap[i]?.entries?.joinToString("\n") 
      { (k, v) -> "$k:$v" }}"
    }
}

fun pbmplus(left: ParikhBoundsMap, other: ParikhBoundsMap) =
  (left.keys + other.keys).associateWith {
    (left[it] ?: emptyMap()) + (other[it] ?: emptyMap())
  }

infix fun IntRange.merge(other: IntRange) =
  minOf(start, other.first)..maxOf(last, other.last)

operator fun ParikhBounds.plus(other: ParikhBounds) =
  (keys + other.keys).associateWith {
    (get(it) ?: 0..0) merge (other[it] ?: 0..0)
  }

operator fun ParikhBounds.times(other: ParikhBounds) =
  (keys + other.keys).associateWith {
    (get(it) ?: 0..0) join (other[it] ?: 0..0)
  }

infix fun IntRange.join(other: IntRange) =
  (first + other.first)..(last + other.last)