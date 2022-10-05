val nexusUser = sys.props.getOrElse("nexus.user", "?")
val nexusPassword = sys.props.getOrElse("nexus.password", "?")

organization := "net.ripe"

name := "rpki-publication-server"

version := "2.0-SNAPSHOT"

scalaVersion := "2.13.9"

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// packaging:
// use sbt assembly plugin and create a fat jar with a predictable name.
mainClass in assembly := Some("net.ripe.rpki.publicationserver.Boot")
assemblyJarName in assembly := "rpki-publication-server.jar"
assemblyMergeStrategy in assembly := {
  case "module-info.class" => MergeStrategy.discard
  case x => (assemblyMergeStrategy in assembly).value (x)
}
test in assembly := {}

parallelExecution in Test := false

fork in run := true

javaOptions in run ++= Seq("-Xmx2G")

enablePlugins(JavaServerAppPackaging, UniversalDeployPlugin)

libraryDependencies ++= {
  val akkaV = "2.6.20"
  val akkaHttp = "10.2.10"
  Seq(
    "com.typesafe.akka"        %% "akka-http"             % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-core"        % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-testkit"     % akkaHttp  % "test",
    "com.typesafe.akka"        %% "akka-http-spray-json"  % akkaHttp,
    "com.typesafe.akka"        %% "akka-stream-testkit"   % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"          % akkaV     % "test",
    "com.typesafe.akka"        %% "akka-slf4j"            % akkaV,
    "org.scalatest"            %% "scalatest"             % "3.2.14"   % "test",
    "org.mockito"               % "mockito-all"           % "1.10.19" % "test",
    "com.fasterxml.woodstox"    % "woodstox-core"         % "6.3.1",
    "ch.qos.logback"            % "logback-classic"       % "1.2.6",
    "com.softwaremill.macwire" %% "macros"                % "2.4.1" % "provided",
    "com.softwaremill.macwire" %% "macrosakka"            % "2.4.1" % "provided",
    "com.softwaremill.macwire" %% "util"                  % "2.4.1" % "provided",
    "com.softwaremill.macwire" %% "proxy"                 % "2.4.1" % "provided",
    "com.google.guava"          % "guava"                 % "23.0",
    "org.apache.commons"        % "commons-io"            % "1.3.2",
    "io.prometheus"             % "simpleclient"          % "0.12.0",
    "io.prometheus"             % "simpleclient_common"   % "0.12.0",
    "org.scala-lang.modules"   %% "scala-xml"             % "2.0.1",
    "org.scalikejdbc"          %% "scalikejdbc"           % "3.5.+",
    "org.postgresql"            % "postgresql"            % "42.2.23",
    "org.json4s"               %% "json4s-native"         % "4.0.2",
    "org.flywaydb"              % "flyway-core"           % "7.14.1",
    "org.scala-lang.modules"   %% "scala-parallel-collections" % "1.0.3"
  )
}

// Generate the GeneratedBuildInformation object
import java.util.Date
import java.text.SimpleDateFormat
import scala.sys.process._

sourceGenerators in Compile += Def.task {
  val generatedFile = (sourceManaged in Compile).value / "net.ripe.rpki.publicationserver" / "GeneratedBuildInformation.scala"
  val now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
  val rev = sys.env.get("CI_COMMIT_SHORT_SHA") match {
        case Some(sha) => sha
        case None => "git rev-parse --short HEAD".!!.trim()
  }

  val code = s"""package net.ripe.rpki.publicationserver
                object GeneratedBuildInformation {
                val buildDate = "$now"
                val commit = "$rev"
            }""".stripMargin
  IO.write(generatedFile, code.getBytes)
  Seq(generatedFile)
}.taskValue

//Revolver.settings: Seq[sbt.Setting[_]]

credentials += Credentials("Sonatype Nexus Repository Manager",
  "nexus.ripe.net",
  s"$nexusUser",
  s"$nexusPassword")

publishTo := {
  if (sys.props.isDefinedAt("build.number"))
    Some(Resolver.file("",  new File(Path.userHome.absolutePath+"/.m2/repository")))
  else
    Some("ripe-snapshots" at "https://nexus.ripe.net/nexus/content/repositories/snapshots")
}

// Disable the use of the Scala version in output paths and artifacts
crossPaths := false

// Package the initd script. Note: the Universal plugin will make anything in a bin/ directory executable.
mappings in Universal += file("src/main/scripts/rpki-publication-server.sh") -> "bin/rpki-publication-server.sh"
mappings in Universal += file("src/main/resources/reference.conf") -> "conf/rpki-publication-server.default.conf"
mappings in Universal += file("src/main/resources/logback.xml") -> "lib/logback.xml"
