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
  val macwire = "2.5.8"
  Seq(
    "com.typesafe.akka"        %% "akka-http"             % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-core"        % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-testkit"     % akkaHttp  % "test",
    "com.typesafe.akka"        %% "akka-http-spray-json"  % akkaHttp,
    "com.typesafe.akka"        %% "akka-stream-testkit"   % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"          % akkaV     % "test",
    "com.typesafe.akka"        %% "akka-slf4j"            % akkaV,
    "com.typesafe"             %% "ssl-config-core"       % "0.6.1",
    "org.scalatest"            %% "scalatest"             % "3.2.14"   % "test",
    "org.mockito"               % "mockito-all"           % "1.10.19" % "test",
    "com.fasterxml.woodstox"    % "woodstox-core"         % "6.3.1",
    "ch.qos.logback"            % "logback-classic"       % "1.4.3",
    "com.softwaremill.macwire" %% "macros"                % macwire % "provided",
    "com.softwaremill.macwire" %% "macrosakka"            % macwire % "provided",
    "com.softwaremill.macwire" %% "util"                  % macwire % "provided",
    "com.softwaremill.macwire" %% "proxy"                 % macwire % "provided",
    "com.google.guava"          % "guava"                 % "31.1-jre",
    "io.prometheus"             % "simpleclient"          % "0.16.0",
    "io.prometheus"             % "simpleclient_common"   % "0.16.0",
    "org.scala-lang.modules"   %% "scala-xml"             % "2.1.0",
    "org.scalikejdbc"          %% "scalikejdbc"           % "4.0.0",
    "org.postgresql"            % "postgresql"            % "42.5.0",
    "org.json4s"               %% "json4s-native"         % "4.0.6",
    "org.flywaydb"              % "flyway-core"           % "9.4.0",
    "org.scala-lang.modules"   %% "scala-parallel-collections" % "1.0.4"
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
