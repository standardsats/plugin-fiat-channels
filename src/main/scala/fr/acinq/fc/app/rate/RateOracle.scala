package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.pattern.pipe
import fr.acinq.eclair
import fr.acinq.eclair._
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.locks.ReentrantReadWriteLock

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }

  var lastRate: Option[MilliSatoshi] = None
  val rateLock = new ReentrantReadWriteLock()
  val rateWrite = rateLock.writeLock()
  val rateRead = rateLock.readLock()

  def getCurrentRate(): Option[MilliSatoshi] = {
    try {
      rateRead.lock()
      lastRate
    } finally rateRead.unlock()
  }
}

class RateOracle(kit: eclair.Kit, source: RateSource) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, RateOracle.TickUpdateRate)

  override def receive: Receive = {
    case RateOracle.TickUpdateRate =>
      logger.info("Updating current fiat rate")
      source.askRates.pipeTo(self)

    case FiatRate(rate) =>
      logger.info("Got response, rate: " + rate + " EUR/BTC")
      try {
        RateOracle.rateWrite.lock()
        RateOracle.lastRate = Some((math round (100_000_000_000L.toDouble / rate)).msat)
      } finally RateOracle.rateWrite.unlock()

    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }
}
