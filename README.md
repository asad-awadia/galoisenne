# Kaliningraph

[![](https://jitpack.io/v/breandan/kaliningraph.svg)](https://jitpack.io/#breandan/kaliningraph)
[![CI](https://github.com/breandan/kaliningraph/workflows/CI/badge.svg)](https://github.com/breandan/kaliningraph/actions)

Kaliningraph is a purely functional graph library with a DSL for constructing graphs and visualizing the behavior of graph algorithms.

![](http://breandan.net/images/konigsberg_bridges.png)

## Installation

Kaliningraph is hosted on [JitPack](https://jitpack.io/#breandan/kaliningraph/).

### Gradle

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.breandan:kaliningraph:-SNAPSHOT")
}
```

### Maven

```xml
<project>
  <repositories>
    <repository>
      <id>jitpack.io</id>
      <url>https://jitpack.io</url>
    </repository>
  </repositories>
  
  <dependency>
    <groupId>com.github.breandan</groupId>
    <artifactId>kaliningraph</artifactId>
    <version>0.0.1</version>
  </dependency>
</project>
```

### Jupyter Notebook

[First install](https://github.com/kotlin/kotlin-jupyter#installation) the Kotlin Jupyter kernel, then run the `jupyterInstall` task to install the library descriptor:

```
./gradlew jupyterInstall [-Path=~/.jupyter_kotlin/libraries]
```

To access Kotlin∇'s notebook support, use the following line magic:

```
%use kotlingrad
```

For more information, explore the [tutorial](notebooks/Hello%20Kaliningraph.ipynb).

## Graphs, Inductively

What are graphs? A [graph](src/main/kotlin/edu/mcgill/kaliningraph/Graph.kt) is a (possibly empty) set of vertices.

What are vertices? A vertex is a unique label with neighbors (possibly containing itself).

What are neighbors? Neighbors are a graph.

## Getting Started

Run [the demo](src/main/kotlin/edu/mcgill/kaliningraph/HelloKaliningraph.kt) via `./gradlew HelloKaliningraph` to get started.

## Usage

To construct a graph, the [graph builder DSL](src/main/kotlin/edu/mcgill/kaliningraph/LabeledGraph.kt) provides an small alphabet:

```kotlin
val graph = Graph { a - b - c - d - e; a - c - e }
```

This is the same as:

```kotlin
val abcde = Graph { a - b - c - d - e }
val ace = Graph { a - c - e }
val graph = abcde + ace
```

Equality is supported using the [Weisfeiler-Lehman](http://www.jmlr.org/papers/volume12/shervashidze11a/shervashidze11a.pdf#page=6) test:

```kotlin
val x = Graph { a - b - c - d - e; a - c - e }
val y = Graph { b - c - d - e - f; b - d - f }
assertEquals(x == y) // true
```

## Visualization

Kaliningraph supports a number of graph visualizations.

### Graphviz

Graph visualization is made possible thanks to [KraphViz](https://github.com/nidi3/graphviz-java#kotlin-dsl).

```kotlin
val de = Graph { d - e }
val dacbe = Graph { d - a - c - b - e }
val dce = Graph { d - c - e }

val abcd = Graph { a - b - c - d }
val cfde = Graph { c - "a" - f - d - e }

val dg = Graph(dacbe, dce, de) + Graph(abcd, cfde)
dg.show()
```

Running the above snippet will cause the following figure to be rendered in the browser:

![](latex/figures/visualization.svg)

### Matrix form

Graph visualization in both DOT and adjacency matrix format is supported.

|DOT Graph|Matrix|
|-----|------|
|![image](latex/figures/random_dot_graph.png)|![image_1](latex/figures/random_matrix.png)|

It is also possible to visualize the state and transition matrices and step through the graph (`./gradlew PrefAttach`).

![transition_diagram](latex/figures/transition_diagram.png)

### Computation graph

Computational notebooks prototyping is also supported.

```
Notebook {
  a = b + c
  f = b - h
}.show()
```

The above snippet should display something like the following:

![](pdg_demo.svg)

## Translation

Bidirectional translation to various graph formats, including [Graphviz](https://github.com/nidi3/graphviz-java), [JGraphT](https://jgrapht.org/guide/UserOverview), [Tinkerpop](https://tinkerpop.apache.org/docs/current/reference/) and [RedisGraph](https://oss.redislabs.com/redisgraph/) is supported:

```kotlin
val g = Graph { a - b - c - a }
        .toJGraphT().toKaliningraph()
        .toTinkerpop().toKaliningraph()
        .toGraphviz().toKaliningraph()
```

## Code2Vec

Code2Vec generation and visualization is supported. The following demo was generated using message passing on the adjacency matrix, for graphs of varying height. The technique to create the embeddings is described [here](https://www.cs.mcgill.ca/~wlh/grl_book/files/GRL_Book-Chapter_5-GNNs.pdf#page=6). We use TSNE to visualize the resulting vectors in 2D, and can clearly distinguish the clusters.

![](src/main/resources/clusters.svg)

## Automata-Based Regex

A regex to NFA compiler is provided. To run the demo, run `./gradlew RegexDemo`. You should see something like this:

![](regex_demo.png)

## References

### Graph theory

* [Solutio problematis ad geometriam situs pertinentis](http://eulerarchive.maa.org/docs/originals/E053.pdf), Leonhard Euler
* [Account of the Icosian Calculus](http://www.kurims.kyoto-u.ac.jp/EMIS/classics/Hamilton/PRIAIcos.pdf), William (Rowan) Hamilton
* [Mathematical Foundations of the GraphBLAS](https://arxiv.org/pdf/1606.05790.pdf)
* [Graph Algorithms in the Language of Linear Algebra](https://epubs.siam.org/doi/book/10.1137/1.9780898719918)

### Graph learning

* [Graph Representation Learning](https://www.cs.mcgill.ca/~wlh/grl_book/), William Hamilton

### Functional graphs

* [Functional programming with structured graphs](http://www.cs.utexas.edu/~wcook/Drafts/2012/graphs.pdf), Bruno Oliveira and William Cook
* [Think Like a Vertex, Behave Like a Function! A Functional DSL for Vertex-Centric Big Graph Processing](http://research.nii.ac.jp/~hu/pub/icfp16.pdf), Kento Emoto et al.
* [Inductive Graphs and Functional Graph Algorithms](http://web.engr.oregonstate.edu/~erwig/papers/InductiveGraphs_JFP01.pdf), Martin Erwig
* [Fully Persistent Graphs – Which One To Choose?](http://web.engr.oregonstate.edu/~erwig/papers/PersistentGraphs_IFL97.pdf)
* [The Program Dependence Graph and its Use for Optimization](https://www.cs.utexas.edu/~pingali/CS395T/2009fa/papers/ferrante87.pdf)

### Automata

* [roll-library](https://github.com/ISCAS-PMC/roll-library)
* [dk.brics.automata](https://github.com/cs-au-dk/dk.brics.automaton)
* [LearnLib](https://github.com/Learnlib/learnlib)

### Software

* [GraphBlas](http://graphblas.org)
* [GraphBLAST](https://github.com/gunrock/graphblast)
* [Bifurcan](https://github.com/lacuna/bifurcan), high-quality JVM implementations of immutable data structures
* [JGraphT](https://doi.org/10.1145/3381449) - A Java Library for Graph Data Structures and Algorithms
* [viz-js](http://viz-js.com/)

### Software Engineering

* [Getting F-Bounded Polymorphism into Shape](https://www.cs.cornell.edu/~ross/publications/shapes/shapes-pldi14-tr.pdf)
* [Frequent Subgraph Analysis and its Software Engineering Applications](https://etd.ohiolink.edu/!etd.send_file?accession=case1496835753068605)