package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.pattern.pipe
import fr.acinq.eclair
import fr.acinq.eclair._
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

case class AskRate()
case class CurrentRate(milliSatoshi: MilliSatoshi)

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }
}

class RateOracle(kit: eclair.Kit, source: RateSource) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, RateOracle.TickUpdateRate)

  var lastRate: MilliSatoshi = 0L.msat

  override def receive: Receive = {
    case RateOracle.TickUpdateRate =>
      logger.info("Updating current fiat rate")
      source.askRates.pipeTo(self)

    case FiatRate(rate) =>
      logger.info("Got response, rate: " + rate + " USD/BTC")
      lastRate = (math round (100_000_000_000L.toDouble / rate)).msat

    case Status.Failure(e) =>
      logger.error("Request failed: " + e)

    case AskRate =>
      sender() ! CurrentRate(lastRate)
  }
}
