package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.util.ByteString
import fr.acinq.eclair
import fr.acinq.eclair._
import fr.acinq.fc.app.rate.{RateSource, FiatRate}
import grizzled.slf4j.Logging
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }
}

class RateOracle(kit: eclair.Kit, source: RateSource) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, RateOracle.TickUpdateRate)

  var lastRate: Double = 0.0

  override def receive: Receive = {
    case RateOracle.TickUpdateRate =>
      logger.info("Updating current fiat rate")
      source.askRates.pipeTo(self)

    case FiatRate(rate) =>
      logger.info("Got response, rate: " + rate + " USD/BTC")
      lastRate = rate

    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }
}
