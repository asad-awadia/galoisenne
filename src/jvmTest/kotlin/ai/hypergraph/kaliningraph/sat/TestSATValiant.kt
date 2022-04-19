package ai.hypergraph.kaliningraph.sat

import ai.hypergraph.kaliningraph.parsing.*
import ai.hypergraph.kaliningraph.tensor.*
import ai.hypergraph.kaliningraph.types.*
import org.junit.jupiter.api.Test
import org.logicng.formulas.Formula
import kotlin.test.*

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant"
*/
class TestSATValiant {
/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testVecJoin"
*/
  @Test
  fun testVecJoin() {
    val cfg = "S -> ( S ) | ( ) | S S".parseCFG()

    val pwrsetSquared = cfg.variables.take(5).depletedPS().let { it * it }
    println("Cardinality:" + pwrsetSquared.size)

    /*
     * Checks that bitvector joins faithfully encode set join, i.e.:
     *
     *      S   ⋈   S' = Z for all subsets S', S' in P(Variables)
     *      ⇵       ⇵    ⇵
     *      V   ☒   V' = Z'
     */
    pwrsetSquared.forEach { (a, b) ->
      with(cfg) {
        assertEquals(toBitVec(join(a, b)), join(toBitVec(a), toBitVec(b)))
        assertEquals(join(a, b), toNTSet(join(toBitVec(a), toBitVec(b))))
      }
    }
  }

  val xujieGrammar = """
       S -> L1 T1
       S -> L2 T2
      T1 -> S R1
      T2 -> S R2
      L1 -> (
      L2 -> [
      R1 -> )
      R2 -> ]
    """

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testXujieExample"
*/
  @Test
  fun testXujieExample() {
    val cfg = xujieGrammar.trimIndent().parseCFG(normalize=false)

    (setOf("T2", "T1", "S") to setOf("T2", "S", "R1", "R2")).let { (a, b) ->
      with(cfg) {
        println(cfg.prettyPrint())
        println("Set A:$a")
        println("Set B:$b")
        println("Join A*B:" + join(a, b))
        println("A:" + toBitVec(a))
        println("B:" + toBitVec(b))

        println("BV join:" + join(toBitVec(a), toBitVec(b)))
        println("Set join:" + toNTSet(join(toBitVec(a), toBitVec(b))))

        assertEquals(toBitVec(join(a, b)), join(toBitVec(a), toBitVec(b)))
        assertEquals(join(a, b), toNTSet(join(toBitVec(a), toBitVec(b))))
      }
    }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testSingleStepMultiplication"
*/
  @Test
  fun testSingleStepMultiplication()  {
    xujieGrammar.trimIndent().parseCFG(normalize = false).let { cfg ->
      (setOf("T1", "S", "L2") to setOf("T2", "S", "R1", "R2")).let { (a, b) ->
        val trueJoin = cfg.join(a, b)
        println("True join: $trueJoin")

        val litA: List<Formula> = cfg.toBitVec(a).toLitVec()
        val satB: List<Formula> = List(cfg.toBitVec(b).size) { i -> BVar("BV$i") }
        val litC: List<Formula> = cfg.toBitVec(cfg.join(a, b)).toLitVec()

        println("\nSolving for B:")
        val solution = (cfg.join(litA, satB) vecEq litC).solve()
        println(solution)
        println("B=" + satB.map { solution[it] ?: false }.decodeWith(cfg))

        val satA: List<Formula> = List(cfg.toBitVec(b).size) { i -> BVar("AV$i") }
        val litB: List<Formula> = cfg.toBitVec(b).toLitVec()

        println("\nSolving for A:")
        val solution1 = (cfg.join(satA, litB) vecEq litC).solve()
        println(solution1)
        println("A=" + satA.map { solution1[it] ?: false }.decodeWith(cfg))

        val satC: List<Formula> = List(cfg.toBitVec(b).size) { i -> BVar("CV$i") }

        println("\nSolving for C:")
        val solution2 = (cfg.join(litA, litB) vecEq satC).solve()
        println(solution2)
        val bitVecJoin = satC.map { solution2[it]!! }.decodeWith(cfg)
        println("C=$bitVecJoin")

        assertEquals(trueJoin, bitVecJoin)
      }
    }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testXujieMethod"
*/
  @Test
  fun testXujieMethod()  {
    val cfg = "S -> ( S ) | [ S ] | [ ] | | ( ) | S S".parseCFG()

    val decodedString = "[_()_[__]_()__"
      .also { println("$it is being synthesized...") }
      .synthesizeFromFPSolving(cfg).first()

    println("$decodedString generated by SATValiant!")

    val isValid = cfg.isValid(decodedString)
    println("$decodedString is${if (isValid) " " else " not "}valid according to SetValiant!")

    assertTrue(isValid)
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testXujieParsingWithFP"
*/
  @Test
  fun testXujieParsingWithFP()  {
    val cfg = """
      S -> A + B | A * B | S + S | S * S
      A -> X | [ A ]
      B -> Y | ( B )
    """.parseCFG()

    "[X_+(_)________________________________________"
      .also { println("$it is being synthesized...") }
      .synthesizeFromFPSolving(cfg).take(10).forEach { decodedString ->
        println("$decodedString generated by fixed point solving!")

        val isValid = cfg.isValid(decodedString)
        println("$decodedString is${if (isValid) " " else " not "}valid according to SetValiant!")

        assertTrue(isValid)
      }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testXujieParsingWithFP1"
*/
  @Test
  fun testXujieParsingWithFP1()  {
    val cfg = """
      S -> A | B | A + B | A * B | S + S | S * S | [ S ] | ( S )
      A -> X | Y
      B -> Y | Z
    """.parseCFG()

    val decodedString = "[X_+(_)_(____"
      .also { println("$it is being synthesized...") }
      .synthesizeFromFPSolving(cfg).first()

    println("$decodedString generated by fixed point solving!")

    val isValid = cfg.isValid(decodedString)
    println("$decodedString is${if (isValid) " " else " not "}valid according to SetValiant!")

    assertTrue(isValid)
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testMultiSAT"
*/
  @Test
  fun testMultiSAT() {
    val cfg = """
      S -> A | B | A + B | A * B | S + S | S * S | [ S ] | ( S )
      A -> X | Y
      B -> X | Z
    """.parseCFG()

    val distinct = mutableSetOf<String>()
    "[X_+(_)_(__________".also { println("$it is being synthesized...") }
      .synthesizeFromFPSolving(cfg).take(10)
      .forEach { decodedString ->
        assertTrue(decodedString !in distinct)
        distinct += decodedString
        println("$decodedString generated by fixed point solving!")

        val isValid = cfg.isValid(decodedString)
        println("$decodedString is${if (isValid) " " else " not "}valid according to SetValiant!")

        assertTrue(isValid)
      }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testAlgebraicLanguage"
*/
  @Test
  fun testAlgebraicLanguage() {
    val cfg = """
      S -> S + S | S * S | S - S | S / S | ( S )
      S -> 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9
      S -> X | Y | Z
    """.parseCFG()

    val decodedString = "X+__Z__*___"
      .also { println("$it is being synthesized...") }
      .synthesizeFromFPSolving(cfg).first()

    println("$decodedString generated by SATValiant!")

    val isValid = cfg.isValid(decodedString)
    println("$decodedString is${if (isValid) " " else " not "}valid according to SetValiant!")

    assertTrue(isValid)
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.sat.TestSATValiant.testMatrixJoin"
*/
  @Test
  fun testMatrixJoin() {
    val cfg = xujieGrammar.parseCFG(false)
    println(cfg.prettyPrint())
    val vars = cfg.variables

    // We only use off-diagonal entries
    val odMat = FreeMatrix(SAT_ALGEBRA, vars.size) { i, j ->
      if(i == j) BLit(true) else BVar("OD${i}_$j")
    }

    fun <T> Set<T>.encodeAsDMatrix(universe: Set<T>) =
      FreeMatrix(SAT_ALGEBRA, universe.size) { i, j ->
        if (i == j) BLit(universe.elementAt(i) in this)
        else odMat[i, j]
      }

    val allSubsetPairs = vars.depletedPS().let { it * it }
    val nonemptyTriples = allSubsetPairs.mapNotNull { (s1, s2) ->
      val s3 = cfg.join(s1, s2)
      if (s3.isEmpty()) null else (s1 to s2 to s3)
    }

//    val designMatrix = FreeMatrix(SAT_ALGEBRA, vars.size) { r, c ->
//      BVar("G${r}_$c")
//    }

    val constraint = nonemptyTriples.take(180).mapIndexed { i, (s1, s2, s3) ->
      val rows = maxOf(s1.size, s2.size)

      val (X, Y, designMatrix) =
        s1.encodeAsMatrix(vars, rows) to
          s2.encodeAsMatrix(vars, rows) to
          s3.encodeAsDMatrix(vars)

//      println("S1: $s1")
//      println(X.toString())
//      println("S2: $s2")
//      println(Y.toString())
//      println("S3: $s3")
//      println(designMatrix)

      // https://dl.acm.org/doi/pdf/10.1145/3318464.3380607
      // http://www.cs.cmu.edu/afs/cs/user/dwoodruf/www/gwwz.pdf
      val tx = X * designMatrix * designMatrix * Y.transpose

      val dontCare = BVar("dc$i")
      val DC = FreeMatrix(SAT_ALGEBRA, rows) { _, _ -> dontCare }
      tx eq DC
    }.reduce { acc, formula -> acc and formula }

//    println("Solving:${nonemptyTriples.size}")

    val solution = constraint.solve()

    val G = FreeMatrix(odMat.data.map { solution[it]?.let { if (it) "1" else "0" } ?: "UNK" })

//    println("Design matrix: $G")

    nonemptyTriples.take(180).shuffled().forEachIndexed { i, (s1, s2, s3) ->
      val rows = maxOf(s1.size, s2.size)

      val (X, Y) =
        s1.encodeAsMatrix(vars, rows) to
          s2.encodeAsMatrix(vars, rows)

      // Synthesized * operator
      val D = FreeMatrix(SAT_ALGEBRA, G.numRows) { i, j ->
        if(i == j) BVar("K$i") else BLit(G[i, j] == "1")
      }

      val tx = (X * D * D * Y.transpose) // * D * is UNSAT but * D * D * is SAT?
      val dontCare = BVar("DDC$i")
      val DC = FreeMatrix(SAT_ALGEBRA, rows) { _, _ -> dontCare }

      val diag = (tx eq DC).solve()

      val actual = diag.keys
        .mapNotNull { "$it".drop(1).toIntOrNull() }
        .toSet().map { vars.elementAt(it) }

//      println("Expected: $s3")
//      println("Actual  : $actual")
    }
  }
}