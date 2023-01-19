package example

import cats.effect.Resource
import org.apache.kafka.streams.KafkaStreams
import KafkaStreams.{State => KState}

object KafkaStreamsApp {

  private val handler = new KafkaStreams.StateListener {
    def onChange(
      newState: KState, 
      oldState: KState
    ): Unit = (oldState, newState) match {
      case (KState.CREATED, KState.RUNNING) =>
        println("KafkaStreams application started")
      case (oldState, KState.PENDING_SHUTDOWN) => 
        println(s"Shutting down KafkaStreams application after $oldState")
      case (_, KState.NOT_RUNNING) =>
        println(s"KafkaStreams application was shut down successfully")
    }
  }
}