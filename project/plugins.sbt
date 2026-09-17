resolvers += "RIPE Nexus third-party mirror" at "https://maven.nexus.ripe.net/repository/maven-public/"

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
addSbtPlugin("com.sonar-scala" % "sbt-sonar" % "2.3.0")
addDependencyTreePlugin
