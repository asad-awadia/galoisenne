@file:Suppress("NonAsciiCharacters")

package ai.hypergraph.kaliningraph.parsing

import ai.hypergraph.kaliningraph.tensor.UTMatrix
import ai.hypergraph.kaliningraph.types.*

fun CFG.sortAll(s: Σᐩ): Set<Σᐩ> =
  try { solveSortedFP(s.tokenizeByWhitespace())[START_SYMBOL]
    ?.map { it.first.tokenizeByWhitespace().filterNot { "ε" in it }.joinToString(" ") }?.toSet() ?: setOf() }
  catch (e: Exception) { e.printStackTrace(); setOf() }

fun CFG.solveSortedFP(
  tokens: List<Σᐩ>,
  utMatrix: UTMatrix<Sort> = initialUTSMatrix(tokens),
) = utMatrix.seekFixpoint().toFullMatrix()[0].last()

fun CFG.initialUTSMatrix(tokens: List<Σᐩ>, bmp: BiMap = bimap): UTMatrix<Sort> =
  UTMatrix(
    ts = tokens.map { token ->
      (if (token == HOLE_MARKER)
        unitReachability.values.flatten().toSet().filter { root ->
          bmp[root].any { it.size == 1 && it.first() in terminals }
        }.toSet()
      else bmp[listOf(token)])
      .associateWith {
        if (token == HOLE_MARKER)
          bmp[it].filter { it.size == 1 && it.first() in terminals && !it.first().isNonterminalStub() }
          .map { it.first() }.toSet().map { it to if ("ε" in token) 0 else 1 }
        else listOf(token to if ("ε" in token) 0 else 1)
      }
    }.toTypedArray()//.also { it.forEach { println("" + it.size + ":" + it) } }
    ,
    algebra = sortwiseAlgebra
  )

// Maintains a sorted list of nonterminal roots and their leaves
val CFG.sortwiseAlgebra: Ring<Sort> by cache {
  Ring.of(
    nil = mapOf(),
    plus = { x, y -> union(x, y) },
    times = { x, y -> join(x, y) }
  )
}

operator fun SRec.plus(s2: SRec): SRec =
  "$first ${s2.first}" to second + s2.second

// X ⊗ Z := { w | <x, z> ∈ X × Z, (w -> xz) ∈ P }
fun CFG.join(s1: Sort, s2: Sort): Sort =
  (s1.keys * s2.keys).map { (x, z) ->
    bimap[listOf(x, z)].also { it.size }.map { it to x to z }
  }.flatten().map { (w, x, z) ->
    ((s1[x] ?: listOf()).toSet() * (s2[z] ?: listOf()).toSet())
      .map { (q, r) ->
//        println("Joining: $w to ${q.first} and ${r.first}")
        w to (q + r)
      }
  }.flatten().groupingBy { it.first }
    .aggregate { _, acc, it, _ ->
      // Maybe only propagate the top N according to metric to avoid blowup
      (acc ?: listOf()) + it.second
    }
//  bimap.L2RHS.entries.mapNotNull { (k, v) ->
//    val q = v.filter { it.size == 2 }.map { (a, b) ->
//      val left = s1[a]
//      val right = s2[b]
//      if (left != null && right != null) {
//        println("left: ${left.size}; right: ${right.size}")
//        (left.toSet() * right.toSet())
//          .map { (q, r) -> q + r }
//      } else listOf()
//    }.flatten().ifEmpty { null }
//    if (q != null) k to q else null
//  }.toMap()

fun union(s1: Sort, s2: Sort): Sort =
  (s1.keys + s2.keys).associateWith { k ->
    ((s1[k] ?: listOf()) + (s2[k] ?: listOf())).sortedBy { it.second }
  }

// Map of root to the possible sets of leaves
// This is like a tree where we do not store the internal nodes
// The same root can have multiple derivations, but we only care about unique leaf sequences
typealias Sort = Map<Σᐩ, List<SRec>>
// Substring and some metric (e.g., number of blanks)
// TODO: Associate a more concrete semantics with second value,
//       but for now just the number of terminals. For example,
//       we could use perplexity of a Markov chain or the length
//       of the longest common substring with the original string.
typealias SRec = Π2<Σᐩ, Int>