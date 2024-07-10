package ai.hypergraph.kaliningraph.parsing

import NUM_CORES
import ai.hypergraph.kaliningraph.*
import ai.hypergraph.kaliningraph.automata.*
import ai.hypergraph.kaliningraph.repair.minimizeFix
import ai.hypergraph.kaliningraph.types.*
import ai.hypergraph.kaliningraph.types.times
import java.util.concurrent.*
import java.util.stream.*
import kotlin.streams.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.*

fun CFG.parallelEnumSeqMinimalWOR(
  prompt: List<String>,
  tokens: List<String>,
  stoppingCriterion: () -> Boolean = { true }
): Sequence<String> =
  startPTree(prompt)?.let {
    (0..<NUM_CORES).toList().parallelStream().map { i ->
      it.sampleStrWithoutReplacement(i)
        .map { it.removeEpsilon() }
        .takeWhile { stoppingCriterion() }
        .distinct()
        .flatMap { minimizeFix(tokens, it.tokenizeByWhitespace()) { this in language } }
        .distinct()
    }.asSequence().flatten()
  } ?: sequenceOf()

fun CFG.parallelEnumSeqMinimalWR(
  prompt: List<String>,
  tokens: List<String>,
  stoppingCriterion: () -> Boolean = { true }
): Sequence<String> =
  startPTree(prompt)?.let {
    (0..<NUM_CORES).toList().parallelStream().map { i ->
      it.sampleWRGD()
        .map { it.removeEpsilon() }
        .takeWhile { stoppingCriterion() }
        .distinct()
        .flatMap { minimizeFix(tokens, it.tokenizeByWhitespace()) { this in language } }
        .distinct()
    }.asSequence().flatten()
  } ?: sequenceOf()

fun CFG.parallelEnumSeqWR(
  prompt: List<String>,
  cores: Int = NUM_CORES,
  stoppingCriterion: () -> Boolean = { true }
): Sequence<String> =
  startPTree(prompt)?.let {
    (0..<cores).toList().parallelStream().map { i ->
      it.sampleWRGD()
        .map { it.removeEpsilon() }
        .takeWhile { stoppingCriterion() }
        .distinct()
    }.asSequence().flatten()
  } ?: sequenceOf()

// When the CFG is acyclic, there is no need to compute the matrix fixpoint
// unless we want to further constrain it to contain specific tokens. In that
// case, we can simply construct the PTree directly from the grammar.
fun CFG.sampleDirectlyWR(
  cores: Int = NUM_CORES,
  stoppingCriterion: () -> Boolean = { true },
): Stream<String> =
  toPTree().let {
    (0..<cores).toList().parallelStream().flatMap { i ->
      it.sampleWRGD()
        .takeWhile { stoppingCriterion() }
        .distinct()
        .asStream()
    }
  }

fun PTree.sampleWithPCFG(
  pcfgTable: Map<Int, Int>,
  cores: Int = NUM_CORES,
  stoppingCriterion: () -> Boolean = { true }
): Stream<String> =
  (0..<cores).toList().parallelStream().flatMap { i ->
    sampleStrWithPCFG5(pcfgTable)
      .takeWhile { stoppingCriterion() }
      .distinct()
      .asStream()
  }

fun PTree.sampleDirectlyWOR(
  cores: Int = NUM_CORES,
  stoppingCriterion: () -> Boolean = { true }
): Stream<String> =
  (0..<cores).toList().parallelStream().flatMap { i ->
    sampleStrWithoutReplacement(cores, i)
      .takeWhile { stoppingCriterion() }
      .distinct()
      .asStream()
  }

fun PTree.sampleDirectlyWORAndScore(
  cores: Int = NUM_CORES,
  stoppingCriterion: () -> Boolean = { true },
  pcfgMap: Map<Π3A<Σᐩ>, Int>, pcfgNorm: Map<Σᐩ, Int>
): Stream<Π2<String, Double>> =
  (0..<cores).toList().parallelStream().flatMap { i ->
    sampleStrWithoutReplacementAndScore(cores, i, pcfgMap, pcfgNorm)
      .takeWhile { stoppingCriterion() }
      .distinctBy { it.first }
      .asStream()
  }

fun CFG.parallelEnumListWR(
  prompt: List<String>,
  cores: Int = NUM_CORES,
  stoppingCriterion: () -> Boolean = { true }
): List<String> =
  startPTree(prompt)?.let {
    (0..<cores).toList().parallelStream().map { i ->
      it.sampleWRGD()
        .map { it.removeEpsilon() }
        .takeWhile { stoppingCriterion() }
        .distinct()
        .toList()
    }.toList().flatten()
  } ?: listOf()

fun CFG.parallelEnumListWOR(
  prompt: List<String>,
  cores: Int,
  stoppingCriterion: () -> Boolean = { true }
): List<String> =
  startPTree(prompt)?.let {
    (0..<cores).toList().parallelStream().map { i ->
      it.sampleStrWithoutReplacement(cores, i)
        .map { it.removeEpsilon() }
        .takeWhile { stoppingCriterion() }
        .distinct()
        .toList()
    }.toList().flatten()
  } ?: listOf()

/**
 * Much faster version of [intersectLevFSA] that leverages parallelism to construct
 * the intersection grammar since we are on the JVM, resulting in a ~10x speedup.
 */

fun CFG.jvmIntersectLevFSA(fsa: FSA): CFG = jvmIntersectLevFSAP(fsa)
//  subgrammar(fsa.alphabet)
//    .also { it.forEach { println("${it.LHS} -> ${it.RHS.joinToString(" ")}") } }
//    .intersectLevFSAP(fsa)

fun CFG.makeLevPTree(toRepair: Σᐩ, levDist: Int = 3, parikhMap: ParikhMap = this.parikhMap): PTree =
  jvmIntersectLevFSAP(makeLevFSA(toRepair, levDist), parikhMap).toPTree()

val BH_TIMEOUT = 9.minutes
val MINFREEMEM = 1000000000L
val MAX_NTS = 4_000_000 // Gives each nonterminal about ~35kb of memory on Xmx=150GB
val MAX_PRODS = 150_000_000

// We pass pm and lbc because cache often flushed forcing them to be reloaded
// and we know they will usually be the same for all calls to this function.
fun CFG.jvmIntersectLevFSAP(fsa: FSA,
                            parikhMap: ParikhMap = this.parikhMap,
                            lbc: List<IntRange> = this.lengthBoundsCache): CFG {
//  if (fsa.Q.size < 650) throw Exception("FSA size was out of bounds")
  var clock = TimeSource.Monotonic.markNow()

  val nts = ConcurrentHashMap.newKeySet<Σᐩ>().apply { add("START") }

  val initFinal =
    (fsa.init * fsa.final).map { (q, r) -> "START" to listOf("[$q~START~$r]") }

  val transits =
    fsa.Q.map { (q, a, r) -> "[$q~$a~$r]".also { nts.add(it) } to listOf(a) }

  // For every production A → σ in P, for every (p, σ, q) ∈ Q × Σ × Q
  // such that δ(p, σ) = q we have the production [p, A, q] → σ in P′.
  val unitProds = unitProdRules(fsa).toSet()
    .onEach { (a, _) -> nts.add(a) }

  // For each production A → BC in P, for every p, q, r ∈ Q,
  // we have the production [p,A,r] → [p,B,q] [q,C,r] in P′.
  val prods = nonterminalProductions.map { (a, b) -> ntMap[a]!! to b.map { ntMap[it]!! } }.toSet()
  val validTriples = fsa.validTriples.map { arrayOf(it.π1.π1, it.π2.π1, it.π3.π1) }

  val ctClock = TimeSource.Monotonic.markNow()
  val ct = (fsa.validPairs * nonterminals.indices.toSet()).toList()
  val ct2 = Array(fsa.states.size) { Array(nonterminals.size) { Array(fsa.states.size) { false } } }
  ct.parallelStream()
    .filter {
      // Checks whether the length bounds for the noterminal (i.e., the range of the number of terminals it can
      // parse) is compatible with the range of path lengths across all paths connecting two states in an FSA.
      // This is a coarse approximation, but is cheaper to compute, so it filters out most invalid triples.
      lbc[it.π3].overlaps(
        fsa.SPLP(it.π1, it.π2)
      ) &&
        // Checks the Parikh map for compatibility between the CFG nonterminals and state pairs in the FSA.
        // This is a finer grained filter, but more expensive to compute, so we use the coarse filter first
        fsa.obeys(it.π1, it.π2, it.π3, parikhMap)
    }.toList().also {
      val fraction = it.size.toDouble() / (fsa.states.size * nonterminals.size * fsa.states.size)
      println("Fraction of valid triples: $fraction")
    }.forEach { ct2[it.π1.π1][it.π3][it.π2.π1] = true }
  println("Precomputed LP constraints in ${ctClock.elapsedNow()}")

  val states = fsa.stateLst
  val allsym = ntLst
  val counter = AtomicInteger(0)
  val lpClock = TimeSource.Monotonic.markNow()
  val binaryProds =
    prods.parallelStream().flatMap {
      if (BH_TIMEOUT < clock.elapsedNow()) throw Exception("Timeout: ${nts.size} nts")
      val (A, B, C) = it.π1 to it.π2[0] to it.π2[1]
      val trip = arrayOf(A, B, C)
      validTriples.stream()
        // CFG ∩ FSA - in general we are not allowed to do this, but it works
        // because we assume a Levenshtein FSA, which is monotone and acyclic.
//        .filter { it.isCompatibleWith(A to B to C, fsa, lengthBoundsCache) }
//        .filter { it.checkCT(trip, ct1).also { if (!it) elimCounter.incrementAndGet() } }
//        .filter { it.obeysLevenshteinParikhBounds(A to B to C, fsa, parikhMap) }
        .filter { it.checkCompatibility(trip, ct2) }
        .map { (a, b, c) ->
          if (MAX_PRODS < counter.incrementAndGet()) throw Exception("∩-grammar has too many productions! (>$MAX_PRODS)")
          val (p, q, r) = states[a] to states[b] to states[c]
          "[$p~${allsym[A]}~$r]".also { nts.add(it) } to listOf("[$p~${allsym[B]}~$q]", "[$q~${allsym[C]}~$r]")
        }
    }.toList()

  val elimCounter = (validTriples.size * prods.size) - binaryProds.size
  println("Levenshtein-Parikh constraints eliminated $elimCounter productions in ${lpClock.elapsedNow()}")

  // !isSyntheticNT() === is START or a terminal
  fun Σᐩ.isSyntheticNT() =
    first() == '[' && length > 1 // && last() == ']' && count { it == '~' } == 2

  val totalProds = binaryProds.size + transits.size + unitProds.size + initFinal.size
  println("Constructed ∩-grammar with $totalProds productions in ${clock.elapsedNow()}")

  clock = TimeSource.Monotonic.markNow()
  return Stream.concat(binaryProds.stream(), (initFinal + transits + unitProds).stream()).parallel()
    .filter { (_, rhs) -> rhs.all { !it.isSyntheticNT() || it in nts } }
    .collect(Collectors.toSet())
    .also { println("Eliminated ${totalProds - it.size} extra productions before normalization") }
    .jvmPostProcess(clock)
    .expandNonterminalStubs(origCFG = this@jvmIntersectLevFSAP)
//    .jdvpNew()
}

// Parallel streaming doesn't seem to be that much faster (yet)?

fun CFG.jvmPostProcess(clock: TimeSource.Monotonic.ValueTimeMark) =
  jvmDropVestigialProductions(clock)
    .jvmElimVarUnitProds()
      .also { println("Normalization eliminated ${size - it.size} productions in ${clock.elapsedNow()}") }
      .freeze()

fun CFG.expandNonterminalStubs(origCFG: CFG) = flatMap {
  if (it.RHS.size != 1 || !it.RHS.first().isNonterminalStub()) listOf(it)
  else origCFG.bimap.NDEPS[it.RHS.first().drop(1).dropLast(1)]!!.map { t -> it.LHS to listOf(t) }
}.toSet().freeze().also { println("Expanded ${it.size - size} nonterminal stubs") }

tailrec fun CFG.jvmElimVarUnitProds(
  toVisit: Set<Σᐩ> = nonterminals,
  vars: Set<Σᐩ> = nonterminals,
  toElim: Σᐩ? = toVisit.firstOrNull()
): CFG {
  fun Production.isVariableUnitProd() = RHS.size == 1 && RHS[0] in vars
  if (toElim == null) return filter { !it.isVariableUnitProd() }
  val varsThatMapToMe =
    asSequence().asStream().parallel()
      .filter { it.RHS.size == 1 && it.RHS[0] == toElim }
      .map { it.LHS }.collect(Collectors.toSet())
  val thingsIMapTo =
    asSequence().asStream().parallel()
      .filter { it.LHS == toElim }.map { it.RHS }
      .collect(Collectors.toSet())
  return (varsThatMapToMe * thingsIMapTo).fold(this) { g, p -> g + p }
    .jvmElimVarUnitProds(toVisit.drop(1).toSet(), vars)
}

// TODO: Incomplete / untested
// Based on: https://zerobone.net/blog/cs/non-productive-cfg-rules/
// Precondition: The CFG must be binarized, i.e., almost CNF but may have useless productions
// Postcondition: The CFG is in Chomsky Normal Form (CNF)
fun CFG.jdvpNew(): CFG {
  println("Total productions: $size")
  val timer = TimeSource.Monotonic.markNow()
  val counter = ConcurrentHashMap<Set<Σᐩ>, LongAdder>()

  // Maps each nonterminal to the set of RHS sets that contain it
  val UDEPS = ConcurrentHashMap<Σᐩ, ConcurrentLinkedQueue<Set<Σᐩ>>>(size)
  // Maps the set of symbols on the RHS of a production to the production
  val NDEPS = ConcurrentHashMap<Set<Σᐩ>, ConcurrentLinkedQueue<Production>>(size).apply {
    put(emptySet(), ConcurrentLinkedQueue())
    this@jdvpNew.asSequence().asStream().parallel().forEach {
      val v = it.second.toSet() // RHS set, i.e., the set of NTs on the RHS of a production
      // If |v| is 1, then the production must be a unit production, i.e, A -> a, b/c A -> B is not binarized
      getOrPut(if(it.second.size == 1) emptySet() else v) { ConcurrentLinkedQueue() }.add(it)
      v.forEach { s -> UDEPS.getOrPut(s) { ConcurrentLinkedQueue() }.add(v) }
      if (v.size == 2) counter.putIfAbsent(v, LongAdder().apply { add(2L) })
    }
  }

  println("Built graph in ${timer.elapsedNow()}: ${counter.size} conjuncts, ${UDEPS.size + NDEPS.size} edges")

  val nextReachable: LinkedHashSet<Set<Σᐩ>> = LinkedHashSet<Set<Σᐩ>>().apply { add(emptySet()) }

  val productive = mutableSetOf<Production>()
  do {
//    println("Next reachable: ${nextReachable.size}, Productive: ${productive.size}")
    val q = nextReachable.removeFirst()
    if (counter[q]?.sum() == 0L || NDEPS[q]?.all { it in productive } == true) continue
    else if (q.size == 2) { // Conjunct
      val dec = counter[q]!!.apply { decrement() }
      if (dec.sum() == 0L) { // Seen both
        NDEPS[q]?.forEach {
          productive.add(it)
          UDEPS[it.LHS]?.forEach { st -> if (st !in productive) nextReachable.addLast(st) }
        }
      } else nextReachable.addLast(q) // Always add back if sum not zero
    } else {
      NDEPS[q]?.forEach {
        productive.add(it)
        UDEPS[it.LHS]?.forEach { st -> if (st !in productive) nextReachable.addLast(st) }
      }
    }
  } while (nextReachable.isNotEmpty())

  println("Eliminated ${size - productive.size} unproductive productions in ${timer.elapsedNow()}")
  println("Resulting in ${productive.size} productions.")

  val QDEPS =
    ConcurrentHashMap<Σᐩ, ConcurrentLinkedQueue<Production>>(size).apply {
      productive.asSequence().asStream().parallel().forEach {
        getOrPut(it.LHS) { ConcurrentLinkedQueue() }.add(it)
      }
    }

  val done = mutableSetOf(START_SYMBOL)
  val nextProd: MutableList<Σᐩ> = mutableListOf(START_SYMBOL)
  val productiveAndReachable = mutableSetOf<Production>()

  do {
    val q = nextProd.removeFirst().also { done += it }
    QDEPS[q]?.forEach { it ->
      productiveAndReachable.add(it)
      it.RHS.forEach { if (it !in done) nextProd += it }
    }
  } while (nextProd.isNotEmpty())

  println("Eliminated ${productive.size - productiveAndReachable.size} unreachable productions in ${timer.elapsedNow()}")
  println("Resulting in ${productiveAndReachable.size} productions.")

  return productiveAndReachable.freeze()
}

fun CFG.jvmDropVestigialProductions(clock: TimeSource.Monotonic.ValueTimeMark): CFG {
  val start = clock.elapsedNow()
  var counter = 0
  val nts: Set<Σᐩ> = asSequence().asStream().parallel().map { it.first }.collect(Collectors.toSet())
  val rw = asSequence().asStream().parallel()
    .filter { prod ->
     if (counter++ % 1000 == 0 && BH_TIMEOUT < clock.elapsedNow()) throw Exception("Timeout! ${clock.elapsedNow()}")
      // Only keep productions whose RHS symbols are not synthetic or are in the set of NTs
      prod.RHS.all { !(it.first() == '[' && 1 < it.length) || it in nts }
    }
    .collect(Collectors.toSet())
    .also { println("Removed ${size - it.size} invalid productions in ${clock.elapsedNow() - start}") }
    .freeze()
    .jvmRemoveUselessSymbols(nts)
  //.jdvpNew()

  println("Removed ${size - rw.size} vestigial productions, resulting in ${rw.size} productions.")

  return if (rw.size == size) rw else rw.jvmDropVestigialProductions(clock)
}

/**
 * Eliminate all non-generating and unreachable symbols.
 *
 * All terminal-producing symbols are generating.
 * If A -> [..] and all symbols in [..] are generating, then A is generating
 * No other symbols are generating.
 *
 * START is reachable.
 * If S -> [..] is reachable, then all variables in [..] are reachable.
 * No other symbols are reachable.
 *
 * A useful symbol is both generating and reachable.
 */

fun CFG.jvmRemoveUselessSymbols(
  nonterminals: Set<Σᐩ>,
  generating: Set<Σᐩ> = jvmGenSym(nonterminals),
  reachable: Set<Σᐩ> = jvmReachSym()
): CFG =
  asSequence().asStream().parallel()
//    .filter { (s, _) -> s in reachable && s in generating }
    .filter { (s, r) -> s in reachable && s in generating && r.all { it in reachable && (r.size == 1 || it in generating) } }
    .collect(Collectors.toSet())

private fun CFG.jvmReachSym(from: Σᐩ = START_SYMBOL): Set<Σᐩ> {
  val allReachable: MutableSet<Σᐩ> = mutableSetOf(from)
  val nextReachable: MutableSet<Σᐩ> = mutableSetOf(from)
  val NDEPS =
    ConcurrentHashMap<Σᐩ, MutableSet<Σᐩ>>(size).apply {
      this@jvmReachSym.asSequence().asStream().parallel()
        .forEach { (l, r) -> getOrPut(l) { ConcurrentHashMap.newKeySet() }.addAll(r) }
    }
//    this@jvmReachSym.asSequence().asStream().parallel()
//      .flatMap { (l, r) -> r.stream().map { l to it } }
//      // List of second elements grouped by first element
//      .collect(Collectors.groupingByConcurrent({ it.first }, Collectors.mapping({ it.second }, Collectors.toSet())))

  while (nextReachable.isNotEmpty()) {
    val t = nextReachable.first()
    nextReachable.remove(t)
    allReachable += t
    nextReachable += (NDEPS[t]?: emptyList())
      .filter { it !in allReachable && it !in nextReachable }
  }

//  println("TERM: ${allReachable.any { it in terminals }} ${allReachable.size}")

  return allReachable
}

private fun CFG.jvmGenSym(
  nonterminals: Set<Σᐩ> = asSequence().asStream().parallel().map { it.LHS }.collect(Collectors.toSet()),
  from: Set<Σᐩ> = asSequence().asStream().parallel()
     .filter { it.RHS.size == 1 && it.RHS[0] !in nonterminals }
     .map { it.LHS }.collect(Collectors.toSet())
): Set<Σᐩ> {
  val allGenerating: MutableSet<Σᐩ> = mutableSetOf()
  val nextGenerating: MutableSet<Σᐩ> = from.toMutableSet()
  val TDEPS =
    ConcurrentHashMap<Σᐩ, MutableSet<Σᐩ>>(size).apply {
      this@jvmGenSym.asSequence().asStream().parallel()
        .forEach { (l, r) -> r.forEach { getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(l) } }
    }
//    this@jvmGenSym.asSequence().asStream().parallel()
//      .flatMap { (l, r) -> r.asSequence().asStream().map { it to l } }
//      // List of second elements grouped by first element
//      .collect(Collectors.groupingByConcurrent({ it.first }, Collectors.mapping({ it.second }, Collectors.toList())))

  while (nextGenerating.isNotEmpty()) {
    val t = nextGenerating.first()
    nextGenerating.remove(t)
    allGenerating += t
    nextGenerating += (TDEPS[t] ?: emptyList())
      .filter { it !in allGenerating && it !in nextGenerating }
  }

//  println("START: ${START_SYMBOL in allGenerating} ${allGenerating.size}")

  return allGenerating
}