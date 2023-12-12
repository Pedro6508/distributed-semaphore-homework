ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

val AkkaVersion = "2.8.0"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

val AkkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "ch.qos.logback" % "logback-classic" % "1.4.7",
)

lazy val root = (project in file("."))
  .settings(
    name := "Broadcast-Algorithms",
    libraryDependencies ++= Seq() ++ AkkaDependencies,
  )
