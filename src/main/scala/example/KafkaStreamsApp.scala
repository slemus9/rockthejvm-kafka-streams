package example

import cats.syntax.all._
import cats.effect.{ Async, Resource, Deferred }
import org.apache.kafka.streams.{ KafkaStreams, Topology }
import KafkaStreams.{State => KState}
import scala.concurrent.Promise
import java.util.Properties
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Canceled
import java.time.Duration

object KafkaStreamsApp {

  def start[F[_]](
    topology: Topology, 
    properties: Properties
  )(
    implicit F: Async[F]
  ) = 
    Resource
      .fromAutoCloseable(F.delay { new KafkaStreams(topology, properties) })
      .use(app => 
        F.async_[Unit] { cb => 

          app.setStateListener { 
            case (_, KState.ERROR) => cb(
              new RuntimeException("KafkaStreams application entered into an error state").asLeft
            )
            case (_, KState.NOT_RUNNING) =>
              cb(().asRight)
            case _ => ()
          }
        
          app.setUncaughtExceptionHandler { (_, e) => 
            cb(e.asLeft)
          }
        }  
      )
      
}