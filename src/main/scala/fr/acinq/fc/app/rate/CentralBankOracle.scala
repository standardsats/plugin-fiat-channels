package fr.acinq.fc.app.rate

import akka.actor.{Actor, Status}
import akka.pattern.pipe
import fr.acinq.eclair
import fr.acinq.eclair._
import grizzled.slf4j.Logging

import java.time.{LocalDateTime, Duration => JDuration}
import java.util.concurrent.locks.ReentrantReadWriteLock
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object CentralBankOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }

  var lastRate: Option[MilliSatoshi] = None
  var maxRate: Option[MilliSatoshi] = None
  var maxLastUpdate: Option[LocalDateTime] = None

  /** We save the maximum rate for some time from current time. User has
   * an oppurtinity to use the highest rate from that moving window.
   * TODO: that window should be smaller when we implement rate negotiation
   * procedure in the FC protocol.
    */
  val WINDOW_SIZE = 48.hours

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

class CentralBankOracle(kit: eclair.Kit, source: CentralBankSource) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, CentralBankOracle.TickUpdateRate)

  override def receive: Receive = {
    case CentralBankOracle.TickUpdateRate =>
      logger.info("Updating current central bank rate")
      source.askRates.pipeTo(self)

    case CentralBankRate(rate) =>
      logger.info("Got response, rate: " + rate + " EUR/BTC")
      try {
        CentralBankOracle.rateWrite.lock()
        val newRate = (math round (100_000_000_000L.toDouble / rate)).msat
        CentralBankOracle.lastRate = Some(newRate)

        CentralBankOracle.maxLastUpdate match {
          case None =>
            CentralBankOracle.maxLastUpdate = Some(LocalDateTime.now)
            CentralBankOracle.maxRate = CentralBankOracle.lastRate
          case Some(lastUpdate) =>
            val now = LocalDateTime.now
            val updateDealine = lastUpdate.plus(JDuration.ofMillis(CentralBankOracle.WINDOW_SIZE.toMillis))
            if (updateDealine.isAfter(now)) {
              CentralBankOracle.maxRate match {
                case Some(value) =>
                  if (value < newRate) {
                    CentralBankOracle.maxRate = Some(newRate)
                    CentralBankOracle.maxLastUpdate = Some(now)
                  }
                case None =>
                  CentralBankOracle.maxRate = Some(newRate)
                  CentralBankOracle.maxLastUpdate = Some(now)
              }
            } else {
              CentralBankOracle.maxRate = Some(newRate)
              CentralBankOracle.maxLastUpdate = Some(now)
            }
        }
      } finally CentralBankOracle.rateWrite.unlock()

      logger.info("Max recent rate: " + CentralBankOracle.maxRate + " EUR/BTC")
    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }
}
