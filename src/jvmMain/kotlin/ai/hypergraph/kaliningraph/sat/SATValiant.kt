package ai.hypergraph.kaliningraph.sat

import ai.hypergraph.kaliningraph.graphs.*
import ai.hypergraph.kaliningraph.image.toHTML
import ai.hypergraph.kaliningraph.parsing.*
import ai.hypergraph.kaliningraph.tensor.*
import ai.hypergraph.kaliningraph.types.*
import ai.hypergraph.kaliningraph.visualization.*
import org.logicng.formulas.Formula
import kotlin.collections.filter

@JvmName("joinFormula")
fun CFG.join(left: List<Formula>, right: List<Formula>): List<Formula> =
  if (left.isEmpty() || right.isEmpty()) emptyList()
  else List(left.size) { i ->
    bimap[variables.elementAt(i)].filter { 1 < it.size }.map { it[0] to it[1] }
      .map { (B, C) -> left[variables.indexOf(B)] and right[variables.indexOf(C)] }
      .fold(BLit(false)) { acc, satf -> acc or satf }
  }

@JvmName("joinBool")
fun CFG.join(left: List<Boolean>, right: List<Boolean>): List<Boolean> =
  List(left.size) { i ->
    bimap[variables.elementAt(i)].filter { 1 < it.size }.map { it[0] to it[1] }
      .map { (B, C) -> left[variables.indexOf(B)] and right[variables.indexOf(C)] }
      .fold(false) { acc, satf -> acc or satf }
  }

infix fun List<Formula>.union(that: List<Formula>): List<Formula> =
  if (isEmpty()) that else if (that.isEmpty()) this
  else List(size) { i -> this[i] or that[i] }

fun List<Boolean>.toLitVec(): List<Formula> = map { BLit(it) }

fun CFG.toBitVec(nonterminals: Set<String>): List<Boolean> = variables.map { it in nonterminals }
fun CFG.toNTSet(nonterminals: List<Boolean>): Set<String> =
  nonterminals.mapIndexedNotNull { i, it -> if(it) variables.elementAt(i) else null }.toSet()

fun List<Boolean>.decodeWith(cfg: CFG): Set<String> =
  mapIndexedNotNull { i, it -> if(it) cfg.variables.elementAt(i) else null }.toSet()

fun List<Formula>.allFalse(): Formula = reduce { acc, satf -> acc or satf }.negate()

infix fun List<Formula>.vecEq(that: List<Formula>): Formula =
  if (isEmpty() && that.isEmpty()) T
  else if (isEmpty()) that.allFalse() else if (that.isEmpty()) this.allFalse()
  else zip(that).map { (a, b) -> a eq b }.reduce { acc, satf -> acc and satf }

infix fun FreeMatrix<List<Formula>>.matEq(that: FreeMatrix<List<Formula>>): Formula =
  data.zip(that.data).map { (a, b) -> a vecEq b }.reduce { acc, satf -> acc and satf }

infix fun FreeMatrix<List<Formula>>.fixedpointMatEq(that: FreeMatrix<List<Formula>>): Formula =
  List(numRows - 2) {
    i -> List(numCols - i - 2) { j -> this[i, i + j + 2] vecEq that[i, i + j + 2] }
        .reduce { acc, satf -> acc and satf }
  }.reduce { acc, satf -> acc and satf }

fun CFG.isInGrammar(mat: FreeMatrix<List<Formula>>): Formula =
  mat[0].last()[variables.indexOf(START_SYMBOL)]

// Encodes the constraint that a bit-vector representing a unary production
// should not contain mixed nonterminals e.g. given A->(, B->(, C->), D->)
// grammar, the bitvector must not have the configuration [A=1 B=1 C=0 D=1],
// it should be either [A=1 B=1 C=0 D=0] or [A=0 B=0 C=1 D=1].
fun CFG.mustBeOnlyOneTerminal(bitvec: List<Formula>): Formula =
  // terminal        set of nonterminals it can represent
  alphabet.map { bimap[listOf(it)] }.map { nts ->
    val (insiders, outsiders) = variables.partition { it in nts }
    (insiders.map { nt -> bitvec[variables.indexOf(nt)] } + // All of these
      outsiders.map { nt -> bitvec[variables.indexOf(nt)].negate() }) // None of these
      .reduce { acc, satf -> acc and satf }
  }.reduce { acc, satf -> acc xor satf }

// Encodes that each blank can only be one nonterminal
fun CFG.uniquenessConstraints(holeVariables: List<List<Formula>>): Formula =
  holeVariables.map { bitvec -> mustBeOnlyOneTerminal(bitvec) }
    .fold(T) { acc, it -> acc and it }

fun CFG.makeSATAlgebra() =
    Ring.of(
      nil = List(variables.size) { F },
      one = List(variables.size) { T },
      plus = { a, b -> a union b },
      times = { a, b -> join(a, b) }
    )

fun FreeMatrix<Set<Tree>>.toGraphTable(): FreeMatrix<String> =
  data.map {
    it.mapIndexed { i, t -> t.toGraph("$i") }
    .fold(LabeledGraph()) { ac, lg -> ac + lg }.html()
  }.let { FreeMatrix(it) }

fun CFG.parseHTML(s: String): String = parseTable(s).toGraphTable().toHTML()

fun CFG.constructSATMatrix(
  words: List<String>,
  holeVariables: MutableList<List<Formula>> = mutableListOf(),
): Π2<FreeMatrix<List<Formula>>, MutableList<List<Formula>>> =
    FreeMatrix(makeSATAlgebra(), words.size + 1) { r, c ->
      if (c == r + 1) {
        val word = words[c - 1]
        if (word == "_") List(variables.size) { k -> BVar("B_${r}_${c}_$k") }
          .also { holeVariables.add(it) } // Blank
        else bimap[listOf(word)].let { nts -> variables.map { BLit(it in nts) } } // Terminal
      } else List(variables.size) { F }
    } to holeVariables

fun CFG.constructInitFixedpointMatrix(
        words: List<String>,
        holeVariables: MutableList<List<Formula>> = mutableListOf(),
): Π2<FreeMatrix<List<Formula>>, MutableList<List<Formula>>> =
    FreeMatrix(makeSATAlgebra(), words.size + 1) { r, c ->
      if (c == r + 1) {
        val word = words[c - 1]
        if (word == "_") List(variables.size) { k -> BVar("B_${r}_${c}_$k") }
                .also { holeVariables.add(it) } // Blank
        else if (word.startsWith("<") && word.endsWith(">"))
            setOf(word.drop(1).dropLast(1)).let { nts -> variables.map { BLit(it in nts) } } // Terminal
        else bimap[listOf(word)].let { nts -> variables.map { BLit(it in nts) } } // Terminal
      }
      else if (c > r + 1) List(variables.size) { k -> BVar("B_${r}_${c}_$k") }
      else emptyList()
    } to holeVariables

fun CFG.nonterminals(bitvec: List<Boolean>): Set<String> =
  bitvec.mapIndexedNotNull { i, it -> if (it) variables.elementAt(i) else null }.toSet()

fun CFG.terminal(
  bitvec: List<Boolean>,
  nonterminals: Set<String> = nonterminals(bitvec)
): String? = alphabet.firstOrNull { word -> bimap[listOf(word)] == nonterminals }

// Summarize fill structure of bit vector variables
fun FreeMatrix<List<Formula>>.fillStructure(): FreeMatrix<String> =
  FreeMatrix(numRows, numCols) { r, c ->
    this[r, c].let {
      if (it.all { it == F }) "0"
      else if (it.all { it in setOf(T, F) }) "LV$r$c"
      else "BV$r$c[len=${it.toString().length}]"
    }
  }

val SAT_ALGEBRA =
  Ring.of(
    nil = BLit(false),
    one = BLit(true),
    plus = { a, b -> a or b },
    times = { a, b -> a and b }
  )

fun List<String>.synthesizeFromFPSolving(cfg: CFG, join: String = ""): Sequence<String> =
  sequence {
    val words: List<String> = this@synthesizeFromFPSolving

    val (fixpointMatrix, holeVariables) = cfg.constructInitFixedpointMatrix(words)

    val valiantParses = cfg.run {
      cfg.isInGrammar(fixpointMatrix) and
        uniquenessConstraints(holeVariables) and
        (fixpointMatrix fixedpointMatEq fixpointMatrix * fixpointMatrix) // TODO: optimize * for UT GEMM (like eqUT)
    }

    var (solver, solution) = valiantParses.let {
        try { it.solveIncremental() } catch (npe: NullPointerException) { return@sequence }
    }
    var isFresh = T
    while (true)
      try {
//        val fpMatrix = FreeMatrix(
//          fixpointMatrix.data.map { bitVec -> bitVec.map { solution[it] ?: false } }
//            .map { cfg.terminal(it) ?: "" }
//        )
//        println(fpMatrix)

        val fillers = holeVariables.map { bitVec -> bitVec.map { solution[it]!! } }
          .map { cfg.terminal(it) }.toMutableList()

        yield(words.joinToString(join) { if (it == "_") fillers.removeAt(0)!! else it })

        val holes = holeVariables.flatten()
        isFresh = isFresh and solution.filter { it.key in holes }.areFresh()

        val model = solver.run { add(isFresh); sat(); model() }
        solution = solution.keys.associateWith { model.evaluateLit(it) }
      } catch (e: Exception) { e.printStackTrace(); break }

    ff.clear()
  }

fun String.synthesizeFromFPSolving(cfg: CFG, join: String = ""): Sequence<String> =
    split(" ").let { if (it.size == 1) map { "$it" } else it }
        .filter(String::isNotBlank).synthesizeFromFPSolving(cfg, join)