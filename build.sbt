import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

val buildNumber = sys.props.getOrElse("build.number", "DEV")
val nexusUser = sys.props.getOrElse("nexus.user", "?")
val nexusPassword = sys.props.getOrElse("nexus.password", "?")

organization := "net.ripe"

name := "rpki-publication-server"

version := "1.1-SNAPSHOT"

scalaVersion := "2.12.11"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

parallelExecution in Test := false

fork in run := true

javaOptions in run ++= Seq("-Xmx2G", "-XX:+UseConcMarkSweepGC")

enablePlugins(JavaServerAppPackaging, UniversalDeployPlugin)
enablePlugins(DockerPlugin)


dockerCommands := Seq(
  Cmd("FROM", "java:latest"),
  Cmd("MAINTAINER", "SWE Green <swe-green@ripe.net>"),
  Cmd("WORKDIR",s"/opt/docker"),
  Cmd("ADD","opt /opt"),
  Cmd("EXPOSE", "7788:7788"),
  ExecCmd("ENTRYPOINT","bin/rpki-publication-server.sh", "run"),
  ExecCmd("CMD", "-c", "../conf/rpki-publication-server.default.conf")
       )

//dockerTag := "rpki-publication-server"


resolvers += "Codehaus Maven2 Repository" at "http://repository.codehaus.org/"

resolvers += "JCenter" at "https://jcenter.bintray.com/"

libraryDependencies ++= {
//  val akkaV = "2.4.20"
  val akkaV = "2.6.5"
  val akkaHttp = "10.1.12"
  Seq(
    "com.typesafe.akka"        %% "akka-http"             % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-core"        % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-testkit"     % akkaHttp,
    "com.typesafe.akka"        %% "akka-http-spray-json"  % akkaHttp,
    "com.typesafe.akka"        %% "akka-stream-testkit"   % akkaV,
    "com.typesafe.akka"        %% "akka-actor"            % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"          % akkaV     % "test",
    "com.typesafe.akka"        %% "akka-slf4j"            % akkaV,
    "org.scalatest"            %% "scalatest"             % "3.0.4"   % "test",
    "org.mockito"               % "mockito-all"           % "1.10.19" % "test",
    "org.codehaus.woodstox"     % "woodstox-core-asl"     % "4.4.1",
    "com.sun.xml.bind"          % "jaxb1-impl"            % "2.2.5.1",
    "ch.qos.logback"            % "logback-classic"       % "1.2.3",
    "com.softwaremill.macwire" %% "macros"                % "2.3.3" % "provided",
    "com.softwaremill.macwire" %% "macrosakka"            % "2.3.3" % "provided",
    "com.softwaremill.macwire" %% "util"                  % "2.3.3" % "provided",
    "com.softwaremill.macwire" %% "proxy"                 % "2.3.3" % "provided",
    "com.google.guava"         %  "guava"                 % "18.0",
    "com.google.code.findbugs" %  "jsr305"                % "3.0.2",
    "org.jetbrains.xodus"      % "xodus-entity-store"     % "1.0.5",
    "org.apache.commons"       % "commons-io"             % "1.3.2",
    "io.prometheus"            % "simpleclient"           % "0.9.0",
    "io.prometheus"            % "simpleclient_common"    % "0.9.0",
    "org.scala-lang.modules"   %% "scala-xml"             % "1.2.0"
  )
}

// Generate the GeneratedBuildInformation object
import java.util.Date
import java.text.SimpleDateFormat
import scala.sys.process._

sourceGenerators in Compile += Def.task {
  val generatedFile = (sourceManaged in Compile).value / "net.ripe.rpki.publicationserver" / "GeneratedBuildInformation.scala"
  val now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
  val rev = Process("git rev-parse HEAD").!!.trim()
  val code = s"""package net.ripe.rpki.publicationserver
                object GeneratedBuildInformation {
                val version = "$buildNumber"
                val buildDate = "$now"
                val revision = "$rev"
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

// Only change the version is there's is explicitly set build.number
version in Universal := {
  if (sys.props.isDefinedAt("build.number"))
    buildNumber
  else
    version.value
}


