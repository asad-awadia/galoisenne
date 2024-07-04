package ai.hypergraph.kaliningraph.parsing

import Grammars.arith
import Grammars.evalArith
import Grammars.seq2parsePythonVanillaCFG
import Grammars.tinyC
import Grammars.toyArith
import ai.hypergraph.kaliningraph.*
import org.kosat.round
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.*

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest"
*/
class SeqValiantTest {
/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testSeqValiant"
*/
  @Test
  fun testSeqValiant() {
    var clock = TimeSource.Monotonic.markNow()
    val detSols = Grammars.seq2parsePythonCFG.noEpsilonOrNonterminalStubs
      .enumSeq(List(20) {"_"})
      .take(10_000).sortedBy { it.length }.toList()

    detSols.forEach { assertTrue("\"$it\" was invalid!") { it in Grammars.seq2parsePythonCFG.language } }

    var elapsed = clock.elapsedNow().inWholeMilliseconds
    println("Found ${detSols.size} determinstic solutions in ${elapsed}ms or ~${detSols.size / (elapsed/1000.0)}/s, all were valid!")

    clock = TimeSource.Monotonic.markNow()
    val randSols = Grammars.seq2parsePythonCFG.noEpsilonOrNonterminalStubs
      .sampleSeq(List(20) { "_" }).take(10_000).toList().distinct()
      .onEach { assertTrue("\"$it\" was invalid!") { it in Grammars.seq2parsePythonCFG.language } }

    // 10k in ~22094ms
    elapsed = clock.elapsedNow().inWholeMilliseconds
    println("Found ${randSols.size} random solutions in ${elapsed}ms or ~${randSols.size / (elapsed/1000.0)}/s, all were valid!")
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testCompareSolvers"
*/
  @Test
  fun testCompareSolvers() {
    val prompt = "_ _ ( _ _ _".tokenizeByWhitespace()
    val enumSeq = measureTimedValue { toyArith.enumSeq(prompt).toSet() }
    val solveSeq = measureTimedValue { toyArith.solveSeq(prompt).toSet() }
    val origSet = measureTimedValue { prompt.solve(toyArith).toSet() }

//  EnumSeq: 584 (842.693834ms)
//  SolvSeq: 584 (3.802375ms)
//  SetCYK: 584 (7.388834667s)
    enumSeq.also { println("EnumSeq: ${it.value.size} (${it.duration})") }.value
    solveSeq.also { println("SolvSeq: ${it.value.size} (${it.duration})") }.value
    origSet.also { println("SetCYK: ${it.value.size} (${it.duration})") }.value

    assertEquals(origSet.value, enumSeq.value, "EnumSeq was missing:" + (origSet.value - enumSeq.value))
    assertEquals(origSet.value, solveSeq.value)
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testBalancedBrackets"
*/
  @Test
  fun testBalancedBrackets()  {
    val cfg = "S -> [ S ] | [ ] | S S".parseCFG().noNonterminalStubs

    println(cfg.prettyPrint())

    cfg.enumSeq("_ _ _ _ _ _".tokenizeByWhitespace())
      .filter { (it.matches(cfg) to it.hasBalancedBrackets())
        .also { (valiant, stack) ->
          // Should never see either of these statements if we did our job correctly
          if (!valiant && stack) println("SeqValiant under-approximated Stack: $it")
          else if (valiant && !stack) println("SeqValiant over-approximated Stack: $it")
          assertFalse(!valiant && stack || valiant && !stack)
        }.first
      }.take(100).toList()
      .also { assertTrue(it.isNotEmpty()) }
      .forEach { decodedString ->
        println("$decodedString generated by SeqValiant!")

        val isValid = decodedString.matches(cfg)
        println("$decodedString is ${if (isValid) "" else "not "}valid according to SetValiant!")

        assertTrue(isValid)
      }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testCheckedArithmetic"
*/
  @Test
  fun testCheckedArithmetic() {
    Grammars.checkedArithCFG.enumSeq("( _ + _ ) * ( _ + _ ) = ( _ * _ ) + ( _ * _ )".tokenizeByWhitespace())
      .take(200).toList().also { assertTrue(it.isNotEmpty()) }
      .map {
        println(it)
        val (left, right) = it.split('=')
        val (ltree, rtree) = arith.parse(left)!! to arith.parse(right)!!
        val (leval, reval) = ltree.evalArith() to rtree.evalArith()
        println("$leval = $reval")
        assertEquals(leval, reval)
        leval
      }.distinct().take(4).toList()
  }

  /*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testRandomCFG"
*/
  @Test
  fun testRandomCFG() {
    fun String.deleteRandomSingleWord() =
      tokenizeByWhitespace().let {
        val delIdx: Int = Random.nextInt(it.size - 1)
        it.subList(0, delIdx) + it.subList(delIdx + 1, it.size)
      }.joinToString(" ")

    generateSequence {
      measureTime {
        val cfg = generateRandomCFG().parseCFG().freeze()
        val results = cfg.enumSeq(List(30) { "_" }).filter { 20 < it.length }.take(10).toList()
        val corruptedResults = results.map { if (Random.nextBoolean()) it else it.deleteRandomSingleWord() }
        preparseParseableLines(cfg, corruptedResults.joinToString("\n"))
      }
    }.take(100).toList().map { it.toDouble(DurationUnit.MILLISECONDS) }
      .also { println("Average time: ${it.average().round(3)}ms, total time ${it.sum().round(3)}ms") }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SetValiantTest.testPythonRepairs"
*/
  @Test
  fun testPythonRepairs() {
    val refStr = "NAME = ( NAME"
    val refLst = refStr.tokenizeByWhitespace()
    val template = List(refLst.size + 3) { "_" }
    println("Solving: $template")
    measureTime {
      Grammars.seq2parsePythonCFG.enumSeq(template)
        .map { it to levenshtein(it, refStr) }
        .filter { it.second < 4 }.distinct().take(100)
        .sortedWith(compareBy({ it.second }, { it.first.length }))
        .onEach { println("Δ=${it.second}: ${it.first}") }
//        .onEach { println("Δ=${levenshtein(it, refStr)}: $it") }
        .toList()
        .also { println("Found ${it.size} solutions!") }
    }.also { println("Finished in ${it.inWholeMilliseconds}ms.") }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testRepairWithNTStub"
*/
  @Test
  fun testRepairWithNTStub() {
    var str = "( ε <term> ) ; "
    assertTrue(str in tinyC.language)
    println(tinyC.parse(str)?.prettyPrint())
    println("First diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).diagonals.first().joinToString(","))
    println("Second diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[1].joinToString(","))
    println("Third diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[2].joinToString(","))
    println("Fourth diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[3].joinToString(","))
    println("Fifth diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[4].joinToString(","))

    str = "( <term> ) ; "
    assertTrue(str in tinyC.language)
    println(tinyC.parse(str)?.prettyPrint())
    println("First diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).diagonals.first().joinToString(","))
    // .seekFixpoint().diagonals
    println("Second diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[1].joinToString(","))
    println("Third diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[2].joinToString(","))
    println("Fourth diagonal: " + tinyC.initialUTMatrix(str.tokenizeByWhitespace()).seekFixpoint().diagonals[3].joinToString(","))

    val refStr = "while ( <term> ) ;"
    tinyC.parse(refStr)?.let { println(it) }
    println(refStr in tinyC.language)
    tinyC.fasterRepairSeq(refStr.tokenizeByWhitespace()).distinct().take(100).forEach {
      println(it)
      assertTrue(it in tinyC.language, "Invalid solution: $it")
    }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testTLArithmetic"
*/
  @Test
  fun testTLArithmetic() {
    val cfg = """
      S1C -> 1
      S2C -> 2
      S3C -> 3
      S4C -> 4
      S -> S1 | S2 | S3 | S4 
      S -> S1 = S1C
      S -> S2 = S2C
      S -> S3 = S3C
      S -> S4 = S4C
      S1 -> S1C
      S2 -> S2C | S1 + S1
      S3 -> S3C | S2 + S1 | S1 + S2
      S4 -> S4C | S3 + S1 | S1 + S3 | S2 + S2
    """.parseCFG().noNonterminalStubs

    println(cfg.prettyPrint())
    println(cfg.parse("3 + 1 = 4")?.prettyPrint())
    println(cfg.enumSeq("_ _ _ = _".tokenizeByWhitespace()).first())
    println(cfg.enumSeq("_ _ _ _ _ _ _ _ + 1 _ _ _ _ _ _ _ _".tokenizeByWhitespace()).first())
    //cfg.parseHTML("3 + 1 = 4").show()
    assertEquals("3 + 1 = 4", cfg.enumSeq("3 + _ = 4".tokenizeByWhitespace()).first().also { println("Got $it")})
    assertEquals("3 + 1 = 4", cfg.enumSeq("_ + 1 = 4".tokenizeByWhitespace()).first().also { println("Got $it")})
    assertEquals("3 + 1 = 4", cfg.enumSeq("3 + 1 = _".tokenizeByWhitespace()).first().also { println("Got $it")})

    assertTrue("3 + 1 = 4".matches(cfg))
    assertTrue("2 + 2 = 4".matches(cfg))
    assertTrue("2 + 1 = 3".matches(cfg))
    assertTrue("1 + 1 + 1 = 3".matches(cfg))
    assertTrue("1 + 1 + 1 + 1 = 4".matches(cfg))
  }

  /*
  ./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testBinaryMinimization"
  */
  @Test
  fun testBinaryMinimization() {
    println(seq2parsePythonVanillaCFG.size)
  }
}