import com.typesafe.sbt.GitVersioning
import sbt.Keys.version
// For getting Scoverage out of the generated POM
import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}

val allProjects = List(
  "execution",
  "catnap",
  "eval",
  "tail",
  "reactive",
  "java"
)

addCommandAlias("ci",         ";ci-jvm ;ci-js")
addCommandAlias("ci-all",     ";ci-jvm ;ci-js ;mimaReportBinaryIssues ;unidoc")
addCommandAlias("ci-js",      s";clean ;coreJS/test:compile ;${allProjects.filter(_ != "java").map(_ + "JS/test").mkString(" ;")}")
addCommandAlias("ci-jvm",     s";clean ;coreJVM/test:compile ;${allProjects.map(_ + "JVM/test").mkString(" ;")} ;mimaReportBinaryIssues")
addCommandAlias("ci-jvm-all", s";ci-jvm ;unidoc")
addCommandAlias("release",    ";project monix ;+clean ;+package ;+publishSigned")

val catsVersion = "1.4.0"
val catsEffectVersion = "1.1.0"
val catsEffectLawsVersion = catsEffectVersion
val jcToolsVersion = "2.1.2"
val reactiveStreamsVersion = "1.0.2"
val scalaTestVersion = "3.0.4"
val minitestVersion = "2.1.1"

// The Monix version with which we must keep binary compatibility.
// https://github.com/typesafehub/migration-manager/wiki/Sbt-plugin
val monixSeries = "3.0.0-RC2"

lazy val doNotPublishArtifact = Seq(
  publishArtifact := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := false,
  publishArtifact in (Compile, packageBin) := false
)

lazy val warnUnusedImport = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 11)) =>
        Seq("-Ywarn-unused-import")
      case _ =>
        Seq("-Ywarn-unused:imports")
    }
  },
  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused-import", "-Ywarn-unused:imports"),
  scalacOptions in Test --= Seq("-Ywarn-unused-import", "-Ywarn-unused:imports")
)

lazy val sharedSettings = warnUnusedImport ++ Seq(
  organization := "io.monix",
  scalaVersion := "2.12.7",
  crossScalaVersions := Seq("2.11.12", "2.12.7"),

  scalacOptions ++= Seq(
    // warnings
    "-unchecked", // able additional warnings where generated code depends on assumptions
    "-deprecation", // emit warning for usages of deprecated APIs
    "-feature", // emit warning usages of features that should be imported explicitly
    // Features enabled by default
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:experimental.macros",
  ),

  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v <= 12 =>
      Seq(
        // possibly deprecated options
        "-Ywarn-inaccessible",
        // absolutely necessary for Iterant
        "-Ypartial-unification",
      )
    case _ =>
      Seq.empty
  }),

  // Force building with Java 8
  initialize := {
    if (sys.props("monix.requireJava8") != "false") {
      val required = "1.8"
      val current  = sys.props("java.specification.version")
      assert(current == required, s"Unsupported build JDK: java.specification.version $current != $required")
    }
  },

  // Targeting Java 6, but only for Scala <= 2.11
  javacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 11 =>
      // generates code with the Java 6 class format
      Seq("-source", "1.6", "-target", "1.6")
    case _ =>
      // For 2.12 we are targeting the Java 8 class format
      Seq("-source", "1.8", "-target", "1.8")
  }),

  // Targeting Java 6, but only for Scala <= 2.11
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 11 =>
      // generates code with the Java 6 class format
      Seq("-target:jvm-1.6")
    case _ =>
      // For 2.12 we are targeting the Java 8 class format
      Seq.empty
  }),

  // Linter
  scalacOptions ++= Seq(
    // Turns all warnings into errors ;-)
    "-Xfatal-warnings",
    // Enables linter options
    "-Xlint:adapted-args", // warn if an argument list is modified to match the receiver
    "-Xlint:nullary-unit", // warn when nullary methods return Unit
    "-Xlint:nullary-override", // warn when non-nullary `def f()' overrides nullary `def f'
    "-Xlint:infer-any", // warn when a type argument is inferred to be `Any`
    "-Xlint:missing-interpolator", // a string literal appears to be missing an interpolator id
    "-Xlint:doc-detached", // a ScalaDoc comment appears to be detached from its element
    "-Xlint:private-shadow", // a private field (or class parameter) shadows a superclass field
    "-Xlint:type-parameter-shadow", // a local type parameter shadows a type already in scope
    "-Xlint:poly-implicit-overload", // parameterized overloaded implicit methods are not visible as view bounds
    "-Xlint:option-implicit", // Option.apply used implicit view
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit
    "-Xlint:package-object-classes", // Class or object defined in package object
  ),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 12 =>
      Seq(
        "-Xlint:inaccessible", // warn about inaccessible types in method signatures
        "-Xlint:by-name-right-associative", // By-name parameter of right associative operator
        "-Xlint:unsound-match" // Pattern match may not be typesafe
      )
    case _ =>
      Seq.empty
  }),

  // Turning off fatal warnings for ScalaDoc, otherwise we can't release.
  scalacOptions in (Compile, doc) ~= (_ filterNot (_ == "-Xfatal-warnings")),

  // For working with partially-applied types
  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.9" cross CrossVersion.binary),

  // ScalaDoc settings
  autoAPIMappings := true,
  scalacOptions in ThisBuild ++= Seq(
    // Note, this is used by the doc-source-url feature to determine the
    // relative path of a given source file. If it's not a prefix of a the
    // absolute path of the source file, the absolute path of that file
    // will be put into the FILE_SOURCE variable, which is
    // definitely not what we want.
    "-sourcepath", file(".").getAbsolutePath.replaceAll("[.]$", "")
  ),

  parallelExecution in Test := false,
  parallelExecution in IntegrationTest := false,
  parallelExecution in ThisBuild := false,
  testForkedParallel in Test := false,
  testForkedParallel in IntegrationTest := false,
  testForkedParallel in ThisBuild := false,
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),

  resolvers ++= Seq(
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
    Resolver.sonatypeRepo("releases")
  ),

  // https://github.com/sbt/sbt/issues/2654
  incOptions := incOptions.value.withLogRecompileOnMacro(false),

  // -- Settings meant for deployment on oss.sonatype.org
  sonatypeProfileName := organization.value,

  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    sys.env.getOrElse("SONATYPE_USER", ""),
    sys.env.getOrElse("SONATYPE_PASS", "")
  ),

  publishMavenStyle := true,
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),

  isSnapshot := version.value endsWith "SNAPSHOT",
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }, // removes optional dependencies

  // For evicting Scoverage out of the generated POM
  // See: https://github.com/scoverage/sbt-scoverage/issues/153
  pomPostProcess := { (node: xml.Node) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: xml.Node): Seq[xml.Node] = node match {
        case e: Elem
          if e.label == "dependency" && e.child.exists(child => child.label == "groupId" && child.text == "org.scoverage") => Nil
        case _ => Seq(node)
      }
    }).transform(node).head
  },

  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  homepage := Some(url("https://monix.io")),
  headerLicense := Some(HeaderLicense.Custom(
    """|Copyright (c) 2014-2018 by The Monix Project Developers.
       |See the project homepage at: https://monix.io
       |
       |Licensed under the Apache License, Version 2.0 (the "License");
       |you may not use this file except in compliance with the License.
       |You may obtain a copy of the License at
       |
       |    http://www.apache.org/licenses/LICENSE-2.0
       |
       |Unless required by applicable law or agreed to in writing, software
       |distributed under the License is distributed on an "AS IS" BASIS,
       |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
       |See the License for the specific language governing permissions and
       |limitations under the License."""
    .stripMargin)),

  scmInfo := Some(
    ScmInfo(
      url("https://github.com/monix/monix"),
      "scm:git@github.com:monix/monix.git"
    )),

  developers := List(
    Developer(
      id="alexelcu",
      name="Alexandru Nedelcu",
      email="noreply@alexn.org",
      url=url("https://alexn.org")
    ))
)

lazy val crossSettings = sharedSettings ++ Seq(
  unmanagedSourceDirectories in Compile += {
    baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala"
  },
  unmanagedSourceDirectories in Test += {
    baseDirectory.value.getParentFile / "shared" / "src" / "test" / "scala"
  }
)

def scalaPartV = Def setting (CrossVersion partialVersion scalaVersion.value)
lazy val crossVersionSharedSources: Seq[Setting[_]] =
  Seq(Compile, Test).map { sc =>
    (unmanagedSourceDirectories in sc) ++= {
      (unmanagedSourceDirectories in sc).value.map { dir =>
        scalaPartV.value match {
          case Some((2, y)) if y == 11 => new File(dir.getPath + "_2.11")
          case Some((2, y)) if y >= 12 => new File(dir.getPath + "_2.12")
        }
      }
    }
  }

lazy val requiredMacroDeps = Seq(
  libraryDependencies ++= Seq(
    scalaOrganization.value % "scala-reflect" % scalaVersion.value % Provided,
    scalaOrganization.value % "scala-compiler" % scalaVersion.value % Provided
  ))

lazy val unidocSettings = Seq(
  unidocProjectFilter in (ScalaUnidoc, unidoc) :=
    inProjects(executionJVM, catnapJVM, evalJVM, tailJVM, reactiveJVM),

  // Exclude monix.*.internal from ScalaDoc
  sources in (ScalaUnidoc, unidoc) ~= (_ filterNot { file =>
    // Exclude all internal Java files from documentation
    file.getCanonicalPath matches "^.*monix.+?internal.*?\\.java$"
  }),

  scalacOptions in (ScalaUnidoc, unidoc) +=
    "-Xfatal-warnings",
  scalacOptions in (ScalaUnidoc, unidoc) --=
    Seq("-Ywarn-unused-import", "-Ywarn-unused:imports"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.title(s"Monix"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.sourceUrl(s"https://github.com/monix/monix/tree/v${version.value}€{FILE_PATH}.scala"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Seq("-doc-root-content", file("rootdoc.txt").getAbsolutePath),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.version(s"${version.value}")
)

lazy val testSettings = Seq(
  testFrameworks := Seq(new TestFramework("minitest.runner.Framework")),
  libraryDependencies ++= Seq(
    "io.monix" %%% "minitest-laws" % minitestVersion % Test,
    "org.typelevel" %%% "cats-laws" % catsVersion % Test,
    "org.typelevel" %%% "cats-effect-laws" % catsEffectVersion % Test
  )
)

lazy val javaExtensionsSettings = sharedSettings ++ testSettings ++ Seq(
  name := "monix-java"
)

lazy val scalaJSSettings = Seq(
  coverageExcludedFiles := ".*",

  // Use globally accessible (rather than local) source paths in JS source maps
  scalacOptions += {
    val tagOrHash =
      if (isSnapshot.value) git.gitHeadCommit.value.get
      else s"v${git.baseVersion.value}"
    val l = (baseDirectory in LocalRootProject).value.toURI.toString
    val g = s"https://raw.githubusercontent.com/monix/monix/$tagOrHash/"
    s"-P:scalajs:mapSourceURI:$l->$g"
  }
)

lazy val cmdlineProfile =
  sys.env.getOrElse("SBT_PROFILE", "")

def mimaSettings(projectName: String) = Seq(
  mimaPreviousArtifacts := Set("io.monix" %% projectName % monixSeries),
  mimaBinaryIssueFilters ++= MimaFilters.changesFor_3_0_0
)

def profile: Project ⇒ Project = pr => cmdlineProfile match {
  case "coverage" => pr
  case _ => pr.disablePlugins(scoverage.ScoverageSbtPlugin)
      .enablePlugins(AutomateHeaderPlugin)
}

lazy val doctestTestSettings = Seq(
  doctestTestFramework := DoctestTestFramework.Minitest,
  doctestIgnoreRegex := Some(s".*TaskApp.scala|.*reactive.internal.(builders|operators|rstreams).*"),
  doctestOnlyCodeBlocksMode := true
)

lazy val monix = project.in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .configure(profile)
  .aggregate(coreJVM, coreJS)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)
  .settings(unidocSettings)

lazy val coreJVM = project.in(file("monix/jvm"))
  .configure(profile)
  .dependsOn(executionJVM, catnapJVM, evalJVM, tailJVM, reactiveJVM, javaJVM)
  .aggregate(executionJVM, catnapJVM, evalJVM, tailJVM, reactiveJVM, javaJVM)
  .settings(crossSettings)
  .settings(name := "monix")

lazy val coreJS = project.in(file("monix/js"))
  .configure(profile)
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(executionJS, catnapJS, evalJS, tailJS, reactiveJS)
  .aggregate(executionJS, catnapJS, evalJS, tailJS, reactiveJS)
  .settings(crossSettings)
  .settings(scalaJSSettings)
  .settings(name := "monix")

lazy val executionCommon = crossVersionSharedSources ++ Seq(
  name := "monix-execution",
  // Filtering out breaking changes from 3.0.0
  libraryDependencies += "org.typelevel" %%% "cats-effect" % catsEffectVersion
)

lazy val executionJVM = project.in(file("monix-execution/jvm"))
  .configure(profile)
  .settings(crossSettings)
  .settings(testSettings)
  .settings(requiredMacroDeps)
  .settings(executionCommon)
  .settings(libraryDependencies += "org.jctools" % "jctools-core" % jcToolsVersion)
  .settings(libraryDependencies += "org.reactivestreams" % "reactive-streams" % reactiveStreamsVersion)
  .settings(mimaSettings("monix-execution"))

lazy val executionJS = project.in(file("monix-execution/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .settings(crossSettings)
  .settings(scalaJSSettings)
  .settings(testSettings)
  .settings(requiredMacroDeps)
  .settings(executionCommon)

lazy val catnapCommon =
  crossSettings ++ crossVersionSharedSources ++ testSettings ++ Seq(
    name := "monix-catnap"
  )

lazy val catnapJVM = project.in(file("monix-catnap/jvm"))
  .configure(profile)
  .dependsOn(executionJVM % "compile->compile; test->test")
  .settings(catnapCommon)
  .settings(mimaSettings("monix-catnap"))
  .settings(doctestTestSettings)

lazy val catnapJS = project.in(file("monix-catnap/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(executionJS % "compile->compile; test->test")
  .settings(scalaJSSettings)
  .settings(catnapCommon)

lazy val evalCommon =
  crossSettings ++ crossVersionSharedSources ++ testSettings ++
    Seq(
      name := "monix-eval"
    )

lazy val evalJVM = project.in(file("monix-eval/jvm"))
  .configure(profile)
  .dependsOn(executionJVM % "compile->compile; test->test")
  .dependsOn(catnapJVM)
  .settings(evalCommon)
  .settings(mimaSettings("monix-eval"))
  .settings(doctestTestSettings)

lazy val evalJS = project.in(file("monix-eval/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(executionJS % "compile->compile; test->test")
  .dependsOn(catnapJS)
  .settings(scalaJSSettings)
  .settings(evalCommon)

lazy val tailCommon =
  crossSettings ++ testSettings ++ Seq(
    name := "monix-tail"
  )

lazy val tailJVM = project.in(file("monix-tail/jvm"))
  .configure(profile)
  .dependsOn(evalJVM % "test->test")
  .dependsOn(catnapJVM)
  .settings(tailCommon)
  .settings(doctestTestSettings)
  .settings(mimaSettings("monix-tail"))

lazy val tailJS = project.in(file("monix-tail/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(evalJS % "test->test")
  .dependsOn(catnapJS)
  .settings(scalaJSSettings)
  .settings(tailCommon)

lazy val reactiveCommon =
  crossSettings ++ testSettings ++ Seq(
    name := "monix-reactive"
  )

lazy val reactiveJVM = project.in(file("monix-reactive/jvm"))
  .configure(profile)
  .dependsOn(executionJVM, evalJVM % "compile->compile; test->test")
  .settings(reactiveCommon)
  .settings(mimaSettings("monix-reactive"))
  .settings(doctestTestSettings)

lazy val reactiveJS = project.in(file("monix-reactive/js"))
  .enablePlugins(ScalaJSPlugin)
  .configure(profile)
  .dependsOn(executionJS, evalJS % "compile->compile; test->test")
  .settings(reactiveCommon)
  .settings(scalaJSSettings)

lazy val javaJVM = project.in(file("monix-java"))
  .configure(profile)
  .dependsOn(executionJVM % "provided->compile; test->test")
  .dependsOn(evalJVM % "provided->compile; test->test")
  .settings(javaExtensionsSettings)

lazy val reactiveTests = project.in(file("reactiveTests"))
  .configure(profile)
  .dependsOn(coreJVM)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)
  .settings(
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams-tck" % reactiveStreamsVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    ))

lazy val benchmarksPrev = project.in(file("benchmarks/vprev"))
  .configure(profile)
  .enablePlugins(JmhPlugin)
  .settings(crossSettings)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)
  .settings(
    libraryDependencies += "io.monix" %% "monix" % "3.0.0-RC1"
  )

lazy val benchmarksNext = project.in(file("benchmarks/vnext"))
  .configure(profile)
  .dependsOn(coreJVM)
  .enablePlugins(JmhPlugin)
  .settings(crossSettings)
  .settings(sharedSettings)
  .settings(doNotPublishArtifact)

//------------- For Release

enablePlugins(GitVersioning)

/* The BaseVersion setting represents the in-development (upcoming) version,
 * as an alternative to SNAPSHOTS.
 */
git.baseVersion := "3.0.0-RC2"

val ReleaseTag = """^v(\d+\.\d+(?:\.\d+(?:[-.]\w+)?)?)$""".r
git.gitTagToVersionNumber := {
  case ReleaseTag(v) => Some(v)
  case _ => None
}

git.formattedShaVersion := {
  val suffix = git.makeUncommittedSignifierSuffix(git.gitUncommittedChanges.value, git.uncommittedSignifier.value)

  git.gitHeadCommit.value map { _.substring(0, 7) } map { sha =>
    git.baseVersion.value + "-" + sha + suffix
  }
}

