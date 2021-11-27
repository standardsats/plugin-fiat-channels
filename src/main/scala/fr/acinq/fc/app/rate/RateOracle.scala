package fr.acinq.fc.app.rate

import akka.actor.Actor
import fr.acinq.eclair
import fr.acinq.eclair._
import akka.http.scaladsl.client.RequestBuilding.Get
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }
}

class RateOracle(kit: eclair.Kit) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, RateOracle.TickUpdateRate)

  var lastRate: MilliSatoshi = 0L.msat

  override def receive: Receive = {
    case RateOracle.TickUpdateRate =>
      logger.info("Updating current fiat rate")

      def resp = Get("https://api-pub.bitfinex.com/v2/ticker/tBTCUSD")
      logger.info(s"${resp}")

  }
}
