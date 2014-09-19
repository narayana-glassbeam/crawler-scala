package org.blikk.crawler.processors

import akka.actor._
import akka.stream.actor._
import com.rabbitmq.client.{Connection => RabbitConnection, Channel => RabbitChannel, AMQP}
import org.apache.commons.lang3.SerializationUtils
import org.blikk.crawler.RabbitExchangeDefinition
import scala.util.{Try, Success, Failure}

object RabbitMQSink {
  def props[A](conn: RabbitConnection, rabbitExchange: RabbitExchangeDefinition, 
    ser: A => (Array[Byte], String)) = Props(classOf[RabbitMQSink[A]], conn, rabbitExchange, ser)
}

/**
  * Subscribes to the stream of items and publishes them into RabbitMQ
  */
class RabbitMQSink[A](conn: RabbitConnection, rabbitExchange: RabbitExchangeDefinition)
  (ser: A => (Array[Byte], String)) extends Actor with ActorLogging with ActorSubscriber {

  import ActorSubscriberMessage._

  // What is a good value?
  def requestStrategy = WatermarkRequestStrategy(100)

  // Use a different channel on each thread
  lazy val rabbitMQChannel = new ThreadLocal[RabbitChannel] {
    override def initialValue = conn.createChannel()
  }

  override def preStart(){
    log.info("starting")
    log.info("initializing RabbitMQ exchange {}", rabbitExchange.name)
    rabbitMQChannel.get().exchangeDeclare(rabbitExchange.name, 
      rabbitExchange.exchangeType, rabbitExchange.durable) 
  }

  def receive = {
    case next @ OnNext(item) => 
      Try(item.asInstanceOf[A]) match {
        case Success(item) => writeData(item)
        case Failure(item) => log.error("Received unexpected type: {}", item)
      }
    case complete @ OnComplete =>
      log.info("stream is finished. shutting down.")
      context.stop(self)
    case err @ OnError(cause) =>
      log.error("stream error, shutting down: {}", cause.toString)
      context.stop(self)
  }

  /* Writes the item to RabbitMQ */
  def writeData(item: A) : Unit = {
    val Tuple2(serializedItem, routingKey) = ser(item)
    val channel = rabbitMQChannel.get()
    log.debug("writing numBytes={} to RabbitMQ", serializedItem.size)
    channel.basicPublish(rabbitExchange.name, routingKey, null, serializedItem)
  }


}