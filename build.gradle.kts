import org.gradle.api.JavaVersion.VERSION_11

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `maven-publish`
  kotlin("jvm") version "1.5.20-M1"
  kotlin("jupyter.api") version "0.10.0-42"
  id("com.github.ben-manes.versions") version "0.38.0"
}

group = "com.github.breandan"
version = "0.1.7"

repositories {
  mavenCentral()
  maven("https://jitpack.io")
  maven("https://clojars.org/repo")
}

dependencies {
  implementation(platform(kotlin("bom")))
  implementation(kotlin("stdlib"))
  implementation(kotlin("reflect"))

  val ejmlVersion = "0.40"
  api("org.ejml:ejml-kotlin:$ejmlVersion")
  api("org.ejml:ejml-all:$ejmlVersion")
  api("guru.nidi:graphviz-kotlin:0.18.1")

  implementation("org.slf4j:slf4j-simple:1.7.30")

  testImplementation("com.github.breandan:tensor:master-SNAPSHOT")

  val multikVersion = "0.0.1"
  testImplementation("org.jetbrains.kotlinx:multik-api:$multikVersion")
  testImplementation("org.jetbrains.kotlinx:multik-default:$multikVersion")

  testImplementation(kotlin("scripting-jsr223"))
  testImplementation("com.github.kwebio:kweb-core:0.7.33")
  testImplementation("org.sosy-lab:java-smt:3.7.0")  {
      exclude(group = "uuverifiers", module = "princess_2.13")
    }
  testImplementation("org.sosy-lab:javasmt-solver-mathsat5:5.6.5")

  // http://www.ti.inf.uni-due.de/fileadmin/public/tools/grez/grez-manual.pdf
  // implementation(files("$projectDir/libs/grez.jar"))

  // http://www.informatik.uni-bremen.de/agbkb/lehre/rbs/seminar/AGG-ShortManual.pdf
  // testImplementation(files("$projectDir/libs/aggEngine_V21_classes.jar"))

  // https://github.com/jgralab/jgralab/wiki
  //  testImplementation("de.uni-koblenz.ist:jgralab:8.1.0")

  testImplementation("junit", "junit", "4.13.2")
  testImplementation("com.redislabs:jredisgraph:2.3.0")
  testImplementation("io.lacuna:bifurcan:0.2.0-alpha6")
  testImplementation("org.junit.jupiter:junit-jupiter:5.7.0")

  val jgraphtVersion by extra { "1.5.1" }
  testImplementation("org.jgrapht:jgrapht-core:$jgraphtVersion")
  testImplementation("org.jgrapht:jgrapht-opt:$jgraphtVersion")
  testImplementation("org.jgrapht:jgrapht-ext:$jgraphtVersion")

  val tinkerpopVersion by extra { "3.5.0" }
  testImplementation("org.apache.tinkerpop:gremlin-core:$tinkerpopVersion")
  testImplementation("org.apache.tinkerpop:tinkergraph-gremlin:$tinkerpopVersion")
  testImplementation("info.debatty:java-string-similarity:2.0.0")
}

configure<JavaPluginConvention> {
  sourceCompatibility = VERSION_11
}

tasks {
  compileKotlin {
    kotlinOptions {
      jvmTarget = VERSION_11.toString()
    }
  }

  listOf("HelloKaliningraph", "Rewriter", "PrefAttach",
    "rewriting.CipherSolver", "RegexDemo").forEach { fileName ->
    register(fileName, JavaExec::class) {
      main = "edu.mcgill.kaliningraph.${fileName}Kt"
      classpath = sourceSets["test"].runtimeClasspath
    }
  }

  test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
  }

/*
If overwriting an older version, it is necessary to first run:

rm -rf ~/.m2/repository/com/github/breandan/kaliningraph \
       ~/.ivy2/cache/com.github.breandan/kaliningraph

https://github.com/Kotlin/kotlin-jupyter/issues/121

To deploy to Maven Local and start the notebook, run:

./gradlew [build publishToMavenLocal] jupyterRun -x test
*/

  val jupyterRun by creating(Exec::class) {
    commandLine("jupyter", "notebook", "--notebook-dir=notebooks")
  }

  val sourcesJar by registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
  }
}

publishing {
  publications.create<MavenPublication>("default") {
    from(components["java"])
    artifact(tasks["sourcesJar"])

    pom {
      url.set("https://github.com/breandan/kaliningraph")
      licenses {
        license {
          name.set("The Apache Software License, Version 1.0")
          url.set("http://www.apache.org/licenses/LICENSE-3.0.txt")
          distribution.set("repo")
        }
      }
      developers {
        developer {
          id.set("Breandan Considine")
          name.set("Breandan Considine")
          email.set("bre@ndan.co")
          organization.set("McGill University")
        }
      }
      scm {
        url.set("https://github.com/breandan/kaliningraph")
      }
    }
  }
}