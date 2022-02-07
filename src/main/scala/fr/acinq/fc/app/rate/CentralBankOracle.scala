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

  var lastRate: Double = 0
  var maxLastUpdate: Option[LocalDateTime] = None

  val rateLock = new ReentrantReadWriteLock()
  val rateWrite = rateLock.writeLock()
  val rateRead = rateLock.readLock()

  def getCurrentRate(): Double = {
    try {
      rateRead.lock()
      lastRate
    } finally rateRead.unlock()
  }
}

class CentralBankOracle(kit: eclair.Kit, source: CentralBankSource) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 12.hours, self, CentralBankOracle.TickUpdateRate)

  override def receive: Receive = {
    case CentralBankOracle.TickUpdateRate =>
      logger.info("Updating current central bank rate")
      source.askRates.pipeTo(self)
    case CentralBankRate(0, ticker, asset) =>
      logger.error(s"No such pair ${asset}/${ticker}")
    case CentralBankRate(rate, ticker, asset) =>
     logger.info("Got response, rate: " + rate + s" ${asset}/${ticker}")
      try {
        CentralBankOracle.rateWrite.lock()
        CentralBankOracle.lastRate = rate

        CentralBankOracle.maxLastUpdate match {
          case None =>
            CentralBankOracle.maxLastUpdate = Some(LocalDateTime.now)
          case Some(lastUpdate) =>
            val now = LocalDateTime.now
            CentralBankOracle.maxLastUpdate = Some(now)
        }
      } finally CentralBankOracle.rateWrite.unlock()
      logger.info("Recent rate: " + CentralBankOracle.lastRate + s" ${asset}/${ticker}")
    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }
}
