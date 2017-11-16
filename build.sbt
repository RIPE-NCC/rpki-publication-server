import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

val buildNumber = sys.props.getOrElse("build.number", "DEV")
val nexusUser = sys.props.getOrElse("nexus.user", "?")
val nexusPassword = sys.props.getOrElse("nexus.password", "?")

organization := "net.ripe"

name := "rpki-publication-server"

version := "1.1-SNAPSHOT"

scalaVersion := "2.11.11"

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

dockerTarget in Docker := "rpki-publication-server"

resolvers += "Codehaus Maven2 Repository" at "http://repository.codehaus.org/"

resolvers += "JCenter" at "http://jcenter.bintray.com/"

libraryDependencies ++= {
  val akkaV = "2.4.17"
  val sprayV = "1.3.4"
  Seq(
    "io.spray"                 %% "spray-can"         % sprayV,
    "io.spray"                 %% "spray-routing"     % sprayV,
    "io.spray"                 %% "spray-testkit"     % sprayV    % "test",
    "com.typesafe.akka"        %% "akka-actor"        % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"      % akkaV     % "test",
    "com.typesafe.akka"        %% "akka-slf4j"        % akkaV,
    "org.scalatest"            %% "scalatest"         % "3.0.4"   % "test",
    "org.mockito"               % "mockito-all"       % "1.10.19" % "test",
    "org.codehaus.woodstox"     % "woodstox-core-asl" % "4.4.1",
    "com.sun.xml.bind"          % "jaxb1-impl"        % "2.2.5.1",
    "ch.qos.logback"            % "logback-classic"   % "1.2.3",
    "com.softwaremill.macwire" %% "macros"            % "1.0.7",
    "com.softwaremill.macwire" %% "runtime"           % "1.0.7",
    "io.spray"                 %% "spray-json"        % "1.3.3",
    "com.google.guava"         %  "guava"             % "18.0",
    "com.google.code.findbugs" %  "jsr305"            % "3.0.2",
    "com.typesafe.slick"       %% "slick"             % "3.2.1",
    
    // TODO Update Derby and add
    // TODO permission org.apache.derby.security.SystemPermission "engine", "usederbyinternals";
    "org.apache.derby"          % "derby"             % "10.11.1.1",
    "org.apache.commons"        % "commons-io"        % "1.3.2"
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
                val version = "$buildNumber"
                val buildDate = "$now"
                val revision = "$rev"
            }""".stripMargin
  IO.write(generatedFile, code.getBytes)
  Seq(generatedFile)
}.taskValue

Revolver.settings: Seq[sbt.Setting[_]]

credentials += Credentials("Sonatype Nexus Repository Manager",
  "nexus.ripe.net",
  s"$nexusUser",
  s"$nexusPassword")

publishTo := {
  if (sys.props.isDefinedAt("build.number"))
    Some(Resolver.file("",  new File(Path.userHome.absolutePath+"/.m2/repository")))
  else
    Some("ripe-snapshots" at "http://nexus.ripe.net/nexus/content/repositories/snapshots")
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


