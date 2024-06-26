package ai.hypergraph.kaliningraph.automata

import ai.hypergraph.kaliningraph.sat.*
import ai.hypergraph.kaliningraph.sat.F
import ai.hypergraph.kaliningraph.sat.T
import ai.hypergraph.kaliningraph.sat.and
import ai.hypergraph.kaliningraph.tensor.FreeMatrix
import ai.hypergraph.kaliningraph.types.*
import org.logicng.formulas.Formula

val ecaSatAlgebra = kernelAlgebra<Formula>()
fun String.toBitVector(): SATVector = map { if (it == '1') T else F }.toTypedArray()
private fun initializeSATECA(len: Int, cc: (Int) -> Formula) =
  FreeMatrix(ecaSatAlgebra, len, 1) { r, c -> PKernel(null, cc(r), null) }

infix fun SATVector.matEq(f: SATVector) =
  zip(f).fold(T) { acc, (a, b) -> acc and (a eq b) }

fun SATVector.evolve(
  steps: Int = 1,
  rule: FKernel<Formula>.() -> Formula = { (π2 and π1.negate()) or (π2 xor π3) },
): SATVector =
  initializeSATECA(size) { this[it] }
    .evolve(steps = steps, rule = rule).data.map { it!!.second!! }.toTypedArray()