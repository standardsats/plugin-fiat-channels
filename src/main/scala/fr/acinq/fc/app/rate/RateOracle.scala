package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.pattern.pipe
import fr.acinq.eclair
import fr.acinq.eclair._
import fr.acinq.fc.app.rate.RateOracle.{maxLastUpdate, maxRate}
import grizzled.slf4j.Logging

import java.time.{Duration => JDuration}
import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.locks.ReentrantReadWriteLock
import scala.concurrent.duration._

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }

  var lastRate: Option[MilliSatoshi] = None
  var maxRate: Option[MilliSatoshi] = None
  var maxLastUpdate: Option[LocalDateTime] = None

  val WINDOW_SIZE = 180.seconds

  val rateLock = new ReentrantReadWriteLock()
  val rateWrite = rateLock.writeLock()
  val rateRead = rateLock.readLock()

  def getCurrentRate(): Option[MilliSatoshi] = {
    try {
      rateRead.lock()
      lastRate
    } finally rateRead.unlock()
  }

  def getMaxRate(): Option[MilliSatoshi] = {
    try {
      rateRead.lock()
      maxRate
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
        val newRate = (math round (100_000_000_000L.toDouble / rate)).msat
        RateOracle.lastRate = Some(newRate)

        RateOracle.maxLastUpdate match {
          case None =>
            RateOracle.maxLastUpdate = Some(LocalDateTime.now)
            RateOracle.maxRate = RateOracle.lastRate
          case Some(lastUpdate) =>
            val now = LocalDateTime.now
            val updateDealine = lastUpdate.plus(JDuration.ofMillis(RateOracle.WINDOW_SIZE.toMillis))
            if (updateDealine.isAfter(now)) {
              RateOracle.maxRate match {
                case Some(value) =>
                  if (value < newRate) {
                    RateOracle.maxRate = Some(newRate)
                    RateOracle.maxLastUpdate = Some(now)
                  }
                case None =>
                  RateOracle.maxRate = Some(newRate)
                  RateOracle.maxLastUpdate = Some(now)
              }
            } else {
              RateOracle.maxRate = Some(newRate)
              RateOracle.maxLastUpdate = Some(now)
            }
        }
      } finally RateOracle.rateWrite.unlock()

      logger.info("Max recent rate: " + RateOracle.maxRate + " EUR/BTC")
    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }
}
