package fr.acinq.fc.app.db

import fr.acinq.eclair.MilliSatoshi
import fr.acinq.fc.app.Ticker
import fr.acinq.fc.app.rate.StoredRate
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import java.time.{LocalDateTime, ZoneOffset}

class RatesDb(db: PostgresProfile.backend.Database) {
  def writeRate(ticker: Ticker, rate: StoredRate): Boolean = {
    val updateTuple = Tuple4(ticker.tag, rate.lastRate.toLong, rate.maxRate.toLong,
      rate.lastUpdate.toEpochSecond(ZoneOffset.UTC))
    if (Blocking.txWrite(Rates.findByTickerUpdatableCompiled(ticker.tag).update(updateTuple), db) == 0)
      Blocking.txWrite(Rates.insertCompiled += Tuple4(
        ticker.tag, rate.lastRate.toLong, rate.maxRate.toLong,
        rate.lastUpdate.toEpochSecond(ZoneOffset.UTC)), db) > 0
    else
      false
  }

  def getRate(ticker: Ticker): Option[StoredRate] = for {
    (_, lastRate, maxRate, lastUpdated) <- Blocking.txRead(Rates.findByTicker(ticker.tag).result.headOption, db)
  } yield StoredRate(MilliSatoshi(lastRate), MilliSatoshi(maxRate), LocalDateTime.ofEpochSecond(lastUpdated, 0, ZoneOffset.UTC) )
}
