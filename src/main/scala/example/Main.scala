package example

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Properties
import scala.concurrent.duration._
import scala.jdk.DurationConverters._
import cats.syntax.all._
import cats.effect.{ IO, IOApp }
import domain._
import serdes.implicits._
import io.circe.generic.auto._
import org.apache.kafka.streams.scala.serialization.Serdes._
import org.apache.kafka.streams.scala.serialization.Serdes
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala.StreamsBuilder
import org.apache.kafka.streams.scala.kstream._
import org.apache.kafka.streams.kstream.{ GlobalKTable, JoinWindows, TimeWindows, Windowed }
import org.apache.kafka.streams.{ Topology, StreamsConfig }
import org.apache.kafka.streams.KafkaStreams

object Main extends IOApp.Simple {

  // Topology builder
  val builder = new StreamsBuilder

  val usersOrdersStream: KStream[UserId, Order] =
    builder.stream[UserId, Order](topics.OrdersByUserTopic)

  val userProfilesTable: KTable[UserId, Profile] =
    builder.table[UserId, Profile](topics.DiscountProfilesByUserTopic)

  val discountsProfilesGTable: GlobalKTable[Profile, Discount] =
    builder.globalTable[Profile, Discount](topics.DiscountsTopic)

  // Transformations
  val expensiveOrders: KStream[UserId, Order] = 
    usersOrdersStream.filter { (_, order) => order.amount >=  1000 }

  val purchasedProductsStream: KStream[UserId, Product] =
    usersOrdersStream.flatMapValues { _.products }

  val productsPurchasedByUsers: KGroupedStream[UserId, Product] =
    purchasedProductsStream.groupByKey // Stateful transformation

  /*
    In this transformation, since we are changing the stream's key, 
    the topic is set for re-partitioning
  */
  val purchasedByFirstLetter: KGroupedStream[String, Product] = 
    purchasedProductsStream.groupBy { (userId, _) => 
      userId.head.toLower.toString
    }

  val numberOfProductsByUser: KTable[UserId, Long] =
    productsPurchasedByUsers.count()

  // Time window
  val every10Secs: TimeWindows = 
    TimeWindows.ofSizeWithNoGrace(10.seconds.toJava)

  // Windowed aggregation
  val numProductsByUserEvery10Secs: KTable[Windowed[UserId], Long] = 
    productsPurchasedByUsers.windowedBy(every10Secs).aggregate(0L) {
      (_, _, cnt) => cnt + 1
    }

  // Joins
  // KStream - KTable Inner Join
  val ordersWithUserProfileStream: KStream[UserId, (Order, Profile)] =
    usersOrdersStream.join[Profile, (Order, Profile)](userProfilesTable) {
      (order, profile) => (order, profile)
    }

  /*
    KStream - KTable Inner Join

    def join[GK, GV, RV](globalKTable: GlobalKTable[GK, GV])(
      keyValueMapper: (K, V) => GK,
      joiner: (V, GV) => RV,
    ): KStream[K, RV]

    keyValueMapper: Transform each (K, V) pair from the KStream[K, V] to
    the GlobalKTable key GK, which is used for joining

    joiner: Join both values from the stream and table (V, GV) into a single
    value RV
  */
  val discountedOrdersStream: KStream[UserId, Order] =
    ordersWithUserProfileStream.join(discountsProfilesGTable)(
      keyValueMapper = { case (_, (_, profile)) => profile },
      joiner = { case ((order, _), discount) => 
        order.copy(amount = order.amount * discount.amount)  
      }
    )

  val paymentsStream: KStream[OrderId, Payment] =
    builder.stream(topics.PaidOrdersTopic)

  // Switching keys requires shuffling to ensure co-paritioning
  val ordersStream: KStream[OrderId, Order] =
    discountedOrdersStream.selectKey { (_, order) => order.orderId }

  /*
    KStream - KStream Inner Join
    
    Join orders with payments to get the orders that have been "PAID"
    bounded by windows of time of 5 minutes (assuming that the time at
    which a payment is done is not to far from the time at which the respective
    order is placed)
  */
  val paidOrders: KStream[OrderId, Order] = {

    def joinOrdersAndPayments (order: Order, payment: Payment): Option[Order] =
      payment.status match {
        case "PAID" => Some(order)
        case _      => None
      }
  
    def joinWindow = JoinWindows.ofTimeDifferenceWithNoGrace(
      5.minutes.toJava
    )

    ordersStream
      .join(paymentsStream)(joinOrdersAndPayments(_, _), joinWindow)
      .flatMapValues { _.toList }
  }

  paidOrders.to(topics.PaidOrdersTopic)

  // Topology
  val topology: Topology = builder.build()

  // Application
  val props = new Properties
  props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.applicationId)
  props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
  props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.stringSerde.getClass)
  
  // val app = new KafkaStreams(topology, props)

  // println(topology.describe())
  // app.start()

  def run: IO[Unit] = 
    KafkaResource
      .make[IO](config.bootstrapServers)
      .use { admin => 
        admin.createSimpleTopics(List(
          topics.OrdersByUserTopic,
          topics.OrdersTopic,
          topics.PaymentsTopic,
          topics.PaidOrdersTopic
        )) >> admin.createSimpleCompactTopics(List(
          topics.DiscountProfilesByUserTopic,
          topics.DiscountsTopic
        )) >> KafkaStreamsApp.start[IO](topology, props, 2.seconds)
      }
}

object config {

  val applicationId = "orders-application"
  val bootstrapServers = "0.0.0.0:9092"
}