import Dependencies._

ThisBuild / scalaVersion := "2.12.8"
ThisBuild / version := "0.1.1"
ThisBuild / organization := "org.v2d2"
ThisBuild / organizationName := "v2d2"
traceLevel := 0
fork := true
cancelable in Global := true

// resolvers += "Glassfish" at "http://maven.glassfish.org/content/repositories/maven.hudson-labs.org"
resolvers += "scalaz-bintray".at("https://dl.bintray.com/scalaz/releases")
resolvers +=
  "Sonatype OSS Snapshots".at("https://oss.sonatype.org/content/repositories/releases")

val akkaV      = "2.5.31"
val akkaHttpV  = "10.1.11"
val scalaTestV = "3.0.5"
val macWireV   = "2.3.3"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

lazy val v2d2 = (project in file("."))
  .settings(
    name := "v2d2",
    // libraryDependencies += scalaTest % Test
    libraryDependencies ++= Seq(
      "com.github.slack-scala-client" %% "slack-scala-client"   % "0.2.9",
      "joda-time"                     % "joda-time"             % "2.10.4",
      "org.mockito"                   % "mockito-core"          % "2.16.0" % "test",
      "com.softwaremill.macwire"      %% "macros"               % macWireV % "provided",
      "com.softwaremill.macwire"      %% "macrosakka"           % macWireV % "provided",
      "com.softwaremill.macwire"      %% "util"                 % macWireV,
      "com.softwaremill.macwire"      %% "proxy"                % macWireV,
      "com.typesafe.akka"             %% "akka-actor"           % akkaV,
      "com.typesafe.akka"             %% "akka-contrib"         % akkaV,
      "com.typesafe.akka"             %% "akka-stream"          % akkaV,
      "com.typesafe.akka"             %% "akka-testkit"         % akkaV,
      "com.typesafe.akka"             %% "akka-http"            % akkaHttpV,
      "com.typesafe.akka"             %% "akka-http-spray-json" % akkaHttpV,
      "com.typesafe.akka"             %% "akka-http-testkit"    % akkaHttpV,
      "org.scalatest"                 %% "scalatest"            % scalaTestV % "test",
      "com.lihaoyi"                   %% "pprint"               % "0.5.5",
      "org.apache.commons"            % "commons-lang3"         % "3.6",
      "com.lihaoyi"                   %% "fastparse"            % "2.1.3",
      "org.apache.commons"            % "commons-text"          % "1.1",
      "com.typesafe.play"             %% "play-json"            % "2.7.4",
      "org.joda"                      % "joda-convert"          % "1.8.1" // https://stackoverflow.com/a/13856382/118587
    )
  )

enablePlugins(DockerPlugin)
dockerfile in docker := {
  val jarFile: File = sbt.Keys.`package`.in(Compile, packageBin).value
  val classpath     = (managedClasspath in Compile).value
  val mainclass =
    mainClass.in(Compile, packageBin).value.getOrElse(sys.error("Expected exactly one main class"))
  val jarTarget = s"/app/${jarFile.getName}"
  // Make a colon separated classpath with the JAR file
  val classpathString = classpath.files
    .map("/app/" + _.getName)
    .mkString(":") + ":" + jarTarget
  new Dockerfile {
    // Base image
    from("openjdk:8-jre-alpine")
    // Add all files on the classpath
    add(classpath.files, "/app/")
    // Add the JAR file
    add(jarFile, jarTarget)
    // On launch run Java with the classpath and the main class
    entryPoint("java", "-cp", classpathString, mainclass)
  }
}

// Uncomment the following for publishing to Sonatype.
// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for more detail.

// ThisBuild / description := "Some descripiton about your project."
// ThisBuild / licenses    := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
// ThisBuild / homepage    := Some(url("https://github.com/example/project"))
// ThisBuild / scmInfo := Some(
//   ScmInfo(
//     url("https://github.com/your-account/your-project"),
//     "scm:git@github.com:your-account/your-project.git"
//   )
// )
// ThisBuild / developers := List(
//   Developer(
//     id    = "Your identifier",
//     name  = "Your Name",
//     email = "your@email",
//     url   = url("http://your.url")
//   )
// )
// ThisBuild / pomIncludeRepository := { _ => false }
// ThisBuild / publishTo := {
//   val nexus = "https://oss.sonatype.org/"
//   if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
//   else Some("releases" at nexus + "service/local/staging/deploy/maven2")
// }
// ThisBuild / publishMavenStyle := true
