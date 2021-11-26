package fr.acinq.fc.app.rate

import fr.acinq.eclair._

class RateOracle {
  var lastRate: MilliSatoshi = 0L.msat

  def queryRate: MilliSatoshi = {
      lastRate = 0L.msat
      lastRate
  }
}
