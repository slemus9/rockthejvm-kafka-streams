package example

import fs2.kafka.KafkaAdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.errors.TopicExistsException
import scala.jdk.CollectionConverters._
import cats.syntax.all._
import cats.ApplicativeThrow
import cats.effect.{ Async, Resource }
import fs2.kafka.AdminClientSettings

final case class KafkaResource[F[_] : ApplicativeThrow](
  private val admin: KafkaAdminClient[F]
) {

  def createSimpleTopics(names: List[String]) =
    admin
      .createTopics(names.map { 
        new NewTopic(_, 1, 1.toShort)  
      })
      .recoverWith {
        case _: TopicExistsException => ().pure
      }

  def createSimpleCompactTopics(names: List[String]) =
    admin
      .createTopics(names.map { 
        new NewTopic(_, 1, 1.toShort)
          .configs(
            Map.from(List(
              TopicConfig.CLEANUP_POLICY_CONFIG -> TopicConfig.CLEANUP_POLICY_COMPACT
            )).asJava
          )
      })
      .recoverWith {
        case _: TopicExistsException => ().pure
      }
}

object KafkaResource {

  def make[F[_] : Async](
    bootstrapServers: String
  ): Resource[F, KafkaResource[F]] = 
    KafkaAdminClient
      .resource(AdminClientSettings(bootstrapServers))
      .map(KafkaResource(_))
      
}