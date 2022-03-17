package ai.hypergraph.kaliningraph.smt

import ai.hypergraph.kaliningraph.tensor.Matrix
import ai.hypergraph.kaliningraph.types.*
import org.sosy_lab.java_smt.SolverContextFactory
import org.sosy_lab.java_smt.SolverContextFactory.Solvers
import org.sosy_lab.java_smt.api.*
import org.sosy_lab.java_smt.api.NumeralFormula.*
import java.math.BigInteger
import kotlin.math.pow
import kotlin.reflect.KProperty

// Builder for SAT/SMT Instance - single use only!
class SMTInstance(
  val solver: Solvers = Solvers.PRINCESS,
  val context: SolverContext = SolverContextFactory.createSolverContext(solver),
  val ifm: IntegerFormulaManager = context.formulaManager.integerFormulaManager,
  val bfm: BooleanFormulaManager = context.formulaManager.booleanFormulaManager,
  val qfm: QuantifiedFormulaManager = context.formulaManager.quantifiedFormulaManager
): IntegerFormulaManager by ifm, QuantifiedFormulaManager by qfm {
  val SMT_ALGEBRA =
    Ring.of(
      nil = Literal(0),
      one = Literal(1),
      plus = { a, b -> a + b },
      times = { a, b -> a * b }
    )

  val SAT_ALGEBRA =
    Ring.of(
      nil = Literal(false),
      one = Literal(true),
      plus = { a, b -> a or b },
      times = { a, b -> a and b }
    )

  fun solve(function: SMTInstance.() -> Unit) = function()
  fun BoolVar(): BoolVrb = BoolVrb(this)
  fun BoolVar(name: String): SATF = SATF(this, bfm.makeVariable(name))
  fun Literal(b: Boolean): SATF = SATF(this, bfm.makeBoolean(b))

  fun IntVar() = IntVrb(this)
  fun IntVar(name: String) = SMTF(this, makeVariable(name))
  fun Literal(i: Int) = SMTF(this, makeNumber(i.toLong()))

  class IntVrb(val smtInstance: SMTInstance) {
    operator fun getValue(nothing: Nothing?, property: KProperty<*>) =
      smtInstance.let { SMTF(it, it.makeVariable(property.name)) }
  }

  class BoolVrb(val smtInstance: SMTInstance) {
    operator fun getValue(nothing: Nothing?, property: KProperty<*>) =
      smtInstance.let { SATF(it, it.bfm.makeVariable(property.name)) }
  }

  fun solveFormula(vararg bs: BooleanFormula) =
    context.newProverEnvironment(SolverContext.ProverOptions.GENERATE_MODELS)
      .use { prover ->
        for (f in bs) prover.addConstraint(f)
        assert(!prover.isUnsat) { "Unsat!" }
        prover.modelAssignments// This may not assign all free variables?
//        associateWith { prover.model.evaluate(it) /*Can be null?*/ }
      }

  fun solveInteger(vararg constraints: BooleanFormula): Map<IntegerFormula, Int> =
    solveFormula(*constraints).associate { it.key as IntegerFormula to (it.value as BigInteger).toInt() }

  fun solveBoolean(vararg constraints: BooleanFormula): Map<BooleanFormula, Boolean> =
    solveFormula(*constraints).associate { it.key as BooleanFormula to it.value as Boolean }

  fun prove(goal: BooleanFormula) =
    context.newProverEnvironment().use { prover ->
      prover.push(goal)
      !prover.isUnsat
    }

  fun wrapInt(input: Any): IntegerFormula =
    when (input) {
      is Number -> makeNumber("$input")
      is SMTF -> input.formula
      is IntegerFormula -> input
      else -> throw NumberFormatException("Bad number $input (${input.javaClass.name})")
    }

  fun wrapBool(input: Any): BooleanFormula =
    when (input) {
      is Boolean -> bfm.makeBoolean(input)
      is SATF -> input.formula
      is BooleanFormula -> input
      else -> throw NumberFormatException("Bad boolean $input (${input.javaClass.name})")
    }

  infix fun Any.pls(b: Any) = add(wrapInt(this), wrapInt(b))

  infix fun Any.mns(b: Any) = subtract(wrapInt(this), wrapInt(b))

  infix fun Any.mul(b: Any) = multiply(wrapInt(this), wrapInt(b))

  infix fun Any.dvd(b: Any) = divide(wrapInt(this), wrapInt(b))

  infix fun Any.pwr(b: Int) = (2..b).fold(wrapInt(this)) { a, _ -> a mul this }

  infix fun Any.lt(b: Any) = lessThan(wrapInt(this), wrapInt(b))
  infix fun Any.gt(b: Any) = greaterThan(wrapInt(this), wrapInt(b))
  infix fun Any.eq(b: Any) =
    if (listOf(this, b).all { it is BooleanFormula || it is Boolean })
      bfm.xor(wrapBool(this), wrapBool(b)).negate()
    else equal(wrapInt(this), wrapInt(b))

  infix fun Any.neq(b: Any) = eq(b).negate()

  fun Any.negate() = bfm.not(wrapBool(this))
  infix fun Any.and(b: Any) = bfm.and(wrapBool(this), wrapBool(b))
  infix fun Any.or(b: Any) = bfm.or(wrapBool(this), wrapBool(b))

  fun Int.pow(i: Int): Int = toInt().toDouble().pow(i).toInt()

  fun <T> makeFormula(
    m1: Matrix<T, *, *>,
    m2: Matrix<T, *, *>,
    ifmap: (T, T) -> BooleanFormula
  ) = m1.rows.zip(m2.rows)
    .map { (a, b) -> a.zip(b).map { (a, b) -> ifmap(a, b) } }
    .flatten().reduce { a, b -> a and b }

  infix fun <T> Matrix<T, *, *>.eq(that: Matrix<T, *, *>): BooleanFormula =
    makeFormula(this, that) { a, b -> a as Any eq b as Any }

  infix fun <T> Matrix<T, *, *>.neq(that: Matrix<T, *, *>): BooleanFormula =
    bfm.not(this eq that)
}

open class SATF(
  open val ctx: SMTInstance,
  val formula: BooleanFormula
): BooleanFormula by formula, Group<SATF> {
  private fun SATF(f: SMTInstance.() -> Any) = SATF(ctx, ctx.wrapBool(ctx.f()))

  override val nil: SATF by lazy { SATF { 0 } }
  override val one: SATF by lazy { SATF { 1 } }
  override fun SATF.plus(t: SATF): SATF = SATF { formula or t.formula }
  override fun SATF.times(t: SATF): SATF = SATF { formula and t.formula }
  infix fun SATF.or(t: SATF): SATF = SATF { formula or t.formula }
  infix fun SATF.and(t: SATF): SATF = SATF { formula and t.formula }
  override fun toString() = formula.toString()
  override fun hashCode() = formula.hashCode()
  override fun equals(other: Any?) =
    other is SATF && other.formula == this.formula ||
      other is BooleanFormula && formula == other
}

open class SMTF(
  open val ctx: SMTInstance,
  val formula: IntegerFormula
): IntegerFormula by formula, Group<SMTF> {
  private fun SMTF(f: SMTInstance.() -> Any) = SMTF(ctx, ctx.wrapInt(ctx.f()))

  override val nil: SMTF by lazy { SMTF { 0 } }
  override val one: SMTF by lazy { SMTF { 1 } }
  override fun SMTF.plus(t: SMTF): SMTF = SMTF { this@SMTF pls t }
  override fun SMTF.times(t: SMTF): SMTF = SMTF { this@SMTF mul t }

  override fun toString() = formula.toString()
  override fun hashCode() = formula.hashCode()
  override fun equals(other: Any?) =
    other is SMTF && other.formula == this.formula ||
      other is IntegerFormula && formula == other
}