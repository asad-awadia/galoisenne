package ai.hypergraph.markovian.experiments

import ai.hypergraph.kaliningraph.visualization.browserCmd
import ai.hypergraph.markovian.pmap
import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.geom.geomDensity
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec

import org.jetbrains.letsPlot.awt.plot.PlotSvgExport
import org.jetbrains.letsPlot.letsPlot
import java.io.File
import kotlin.random.Random

fun Plot.display() =
  File.createTempFile("test", ".svg").also {
    val plotSize = DoubleVector(1000.0, 500.0)
    val plot = PlotSvgExport.buildSvgImageFromRawSpecs( this@display.toSpec(), plotSize)
    it.writeText(plot)
  }.also { ProcessBuilder(browserCmd, it.path).start() }

const val POPCOUNT = 10000

fun compare(vararg samplers: (Double) -> Double): Plot =
  compare(
    *samplers.map { f ->
      (1..POPCOUNT).pmap { f(Random.nextDouble()) }
    }.toTypedArray()
  )

fun compare(vararg samples: List<Double>): Plot =
  letsPlot(
    mapOf<String, Any>(
      "x" to samples.fold(listOf<Double>()) { acc, function -> acc + function },
      "" to samples.mapIndexed { i, s -> List(s.size) { "PDF$i" } }.flatten()
    )
  ).let {
    it + geomDensity(alpha = .3) { x = "x"; fill = "" } + ggsize(500, 250)
  }
