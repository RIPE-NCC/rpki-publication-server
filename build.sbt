val nexusUser = sys.props.getOrElse("nexus.user", "?")
val nexusPassword = sys.props.getOrElse("nexus.password", "?")

organization := "net.ripe"

name := "rpki-publication-server"

version := "1.1-SNAPSHOT"

scalaVersion := "2.13.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

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

resolvers += "Codehaus Maven2 Repository" at "https://repository.codehaus.org/"

resolvers += "JCenter" at "https://jcenter.bintray.com/"

libraryDependencies ++= {
  val akkaV = "2.6.14"
  val akkaHttp = "10.2.4"
  Seq(
    "com.typesafe.akka"        %% "akka-http"             % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-core"        % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-testkit"     % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-spray-json"  % akkaHttp,
    "com.typesafe.akka"        %% "akka-stream-testkit"   % akkaV,
    "com.typesafe.akka"        %% "akka-actor"            % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"          % akkaV     % "test",
    "com.typesafe.akka"        %% "akka-slf4j"            % akkaV,
    "org.scalatest"            %% "scalatest"             % "3.1.4"   % "test",
    "org.mockito"               % "mockito-all"           % "1.10.19" % "test",
    "com.fasterxml.woodstox"    % "woodstox-core"         % "6.2.4",
    "ch.qos.logback"            % "logback-classic"       % "1.2.3",
    "com.softwaremill.macwire" %% "macros"                % "2.3.3" % "provided",
    "com.softwaremill.macwire" %% "macrosakka"            % "2.3.3" % "provided",
    "com.softwaremill.macwire" %% "util"                  % "2.3.3" % "provided",
    "com.softwaremill.macwire" %% "proxy"                 % "2.3.3" % "provided",
    "com.google.guava"         %  "guava"                 % "18.0",
    "org.apache.commons"       % "commons-io"             % "1.3.2",
    "io.prometheus"            % "simpleclient"           % "0.9.0",
    "io.prometheus"            % "simpleclient_common"    % "0.9.0",
    "org.scala-lang.modules"   %% "scala-xml"             % "1.2.0",
    "org.scalikejdbc"          %% "scalikejdbc"           % "3.5.+",
    "org.postgresql"           % "postgresql"             % "42.2.15",
    "org.json4s"               %% "json4s-native"         % "3.7.0-M6",
    "org.flywaydb"             % "flyway-core"            % "6.5.5",
    "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0"
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
