organization := "net.ripe"

name := "rpki-publication-server"

version := sys.props.getOrElse("build.number", "DEV")

scalaVersion := "2.11.6"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

parallelExecution in Test := false

fork in run := true

javaOptions in run ++= Seq("-Xmx2G", "-XX:+UseConcMarkSweepGC")

enablePlugins(JavaServerAppPackaging)

resolvers += "Codehaus Maven2 Repository" at "http://repository.codehaus.org/"

resolvers += "JCenter" at "http://jcenter.bintray.com/"

libraryDependencies ++= {
  val akkaV = "2.3.11"
  val sprayV = "1.3.3"
  Seq(
    "io.spray"                 %% "spray-can"         % sprayV,
    "io.spray"                 %% "spray-routing"     % sprayV,
    "io.spray"                 %% "spray-testkit"     % sprayV    % "test",
    "com.typesafe.akka"        %% "akka-actor"        % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"      % akkaV     % "test",
    "com.typesafe.akka"        %% "akka-slf4j"        % akkaV,
    "org.scalatest"            %% "scalatest"         % "2.2.4"   % "test",
    "org.mockito"               % "mockito-all"       % "1.9.5"   % "test",
    "org.codehaus.woodstox"     % "woodstox-core-asl" % "4.4.1",
    "com.sun.xml.bind"          % "jaxb1-impl"        % "2.2.5.1",
    "org.slf4j"                 % "slf4j-api"         % "1.7.12",
    "org.slf4j"                 % "slf4j-log4j12"     % "1.7.12",
    "com.softwaremill.macwire" %% "macros"            % "1.0.1",
    "com.softwaremill.macwire" %% "runtime"           % "1.0.1",
    "io.spray"                 %% "spray-json"        % "1.3.2",
    "com.google.guava"         %  "guava"             % "18.0",
    "com.google.code.findbugs" %  "jsr305"            % "1.3.9",
    "com.h2database"           %  "h2"                % "1.4.185",
    "com.typesafe.slick"       %% "slick"             % "3.0.0"
  )
}

// Generate the GeneratedBuildInformation object
import java.util.Date
import java.text.SimpleDateFormat

sourceGenerators in Compile += Def.task {
  val generatedFile = (sourceManaged in Compile).value / "net.ripe.rpki.publicationserver" /"GeneratedBuildInformation.scala"
  val now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
  val rev = "git rev-parse HEAD".!!.trim()
  val code = s"""package net.ripe.rpki.publicationserver
                object GeneratedBuildInformation {
                val version = "${version.value}"
                val buildDate = "$now"
                val revision = "$rev"
            }""".stripMargin
  IO.write(generatedFile, code.getBytes)
  Seq(generatedFile)
}.taskValue

Revolver.settings: Seq[sbt.Setting[_]]

// Package the initd script. Note: the Universal plugin will make anything in a bin/ directory executable.
mappings in Universal += file("src/main/scripts/rpki-publication-server.sh") -> "bin/rpki-publication-server.sh"

mappings in Universal += file("src/main/resources/application.conf") -> "conf/rpki-publication-server.default.conf"

