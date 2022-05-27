package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.pattern.pipe
import fr.acinq.eclair
import fr.acinq.eclair._
import fr.acinq.fc.app.Ticker
import grizzled.slf4j.Logging

import java.time.{Duration => JDuration}
import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.locks.ReentrantReadWriteLock
import scala.concurrent.duration._

case class StoredRate(lastRate: MilliSatoshi, maxRate: MilliSatoshi, lastUpdate: LocalDateTime)

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }

  var rates: Map[Ticker, StoredRate] = Map.empty

  /** We save the maximum rate for some time from current time. User has
   * an oppurtinity to use the highest rate from that moving window.
   * TODO: that window should be smaller when we implement rate negotiation
   * procedure in the FC protocol.
    */
  val WINDOW_SIZE = 48.hours

  val rateLock = new ReentrantReadWriteLock()
  val rateWrite = rateLock.writeLock()
  val rateRead = rateLock.readLock()

  def getCurrentRate(ticker: Ticker): Option[MilliSatoshi] = {
    try {
      rateRead.lock()
      rates.get(ticker).map(_.lastRate)
    } finally rateRead.unlock()
  }

  def getMaxRate(ticker: Ticker): Option[MilliSatoshi] = {
    try {
      rateRead.lock()
      rates.get(ticker).map(_.maxRate)
    } finally rateRead.unlock()
  }
}

class RateOracle(kit: eclair.Kit, sources: Map[Ticker, RateSource]) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, RateOracle.TickUpdateRate)

  override def receive: Receive = {
    case RateOracle.TickUpdateRate =>
      logger.info("Updating current fiat rates")
      for ((_, source) <- sources) source.askRates.pipeTo(self)

    case FiatRate(ticker, rate) =>
      logger.info(s"Got response, rate: ${rate} ${ticker.tag}/BTC")
      try {
        RateOracle.rateWrite.lock()
        val current = RateOracle.rates.get(ticker)
        val newRate = (math round (100_000_000_000L.toDouble / rate)).msat

        current match {
          case None =>
            RateOracle.rates += ticker -> StoredRate(newRate, newRate, LocalDateTime.now)
          case Some(rate) =>
            val now = LocalDateTime.now
            val updateDealine = rate.lastUpdate.plus(JDuration.ofMillis(RateOracle.WINDOW_SIZE.toMillis))
            if (updateDealine.isAfter(now)) {
              if (rate.maxRate < newRate) {
                RateOracle.rates += ticker -> StoredRate(newRate, newRate, LocalDateTime.now)
              } else {
                RateOracle.rates += ticker -> StoredRate(newRate, rate.maxRate, LocalDateTime.now)
              }
            } else {
              RateOracle.rates += ticker -> StoredRate(newRate, rate.maxRate, LocalDateTime.now)
            }
        }

        logger.info("Max recent rate: " + RateOracle.rates.get(ticker).map(_.maxRate) + s" ${ticker.tag}/BTC")
      } finally RateOracle.rateWrite.unlock()

    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }
}
