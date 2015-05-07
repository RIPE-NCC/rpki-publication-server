organization := "net.ripe"

version := "0.1"

scalaVersion := "2.10.5"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers += "Codehaus Maven2 Repository" at "http://repository.codehaus.org/"
resolvers += "JCenter" at "http://jcenter.bintray.com/"

libraryDependencies ++= {
  val akkaV = "2.3.9"
  val sprayV = "1.3.3"
  Seq(
    "io.spray"              %% "spray-can"        % sprayV,
    "io.spray"              %% "spray-routing"    % sprayV,
    "io.spray"              %% "spray-testkit"    % sprayV    % "test",
    "com.typesafe.akka"     %% "akka-actor"       % akkaV,
    "com.typesafe.akka"     %% "akka-testkit"     % akkaV     % "test",
    "org.scalatest"         %% "scalatest"        % "2.0"     % "test",
    "org.codehaus.woodstox" % "woodstox-core-asl" % "4.4.1",
    "com.sun.xml.bind"      % "jaxb1-impl"        % "2.2.5.1",
    "org.clapper"           %% "grizzled-slf4j"   % "1.0.2"
  )
}

Revolver.settings: Seq[sbt.Setting[_]]
