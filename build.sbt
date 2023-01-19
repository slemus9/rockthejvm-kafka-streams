import Dependencies._

ThisBuild / scalaVersion := "2.13.8"

Compile / run / fork := true

libraryDependencies ++= Seq(
  Library.catsEffect,
  Library.kafkaClients,
  Library.kafkaStreams,
  Library.kafkaStreamsScala,
  Library.fs2Kafka,
  Library.circeCore,
  Library.circeGeneric,
  Library.circeParser
)