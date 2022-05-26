package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.util.ByteString
import fr.acinq.eclair
import fr.acinq.eclair._
import fr.acinq.eclair.api.serde.JsonSupport.fromByteStringUnmarshaller
import fr.acinq.fc.app.Ticker
import fr.acinq.fc.app.Ticker.EUR_TICKER
import grizzled.slf4j.Logging
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

//ecb stuff
import scala.xml.XML

case class CentralBankRate(rate: Double, ticker: Ticker, asset: Ticker)

trait CentralBankSource {
  def askRates: Future[CentralBankRate]
}

class EcbSource(ticker: Ticker, implicit val system: ActorSystem) extends CentralBankSource {
  val http = Http(system)
  val ecbUri = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

  def askRates: Future[CentralBankRate] = {
    if(ticker == EUR_TICKER)
      return Future(CentralBankRate(1, ticker, EUR_TICKER))

    val xml = XML.load(ecbUri)
    val cubes = (xml \\ "Cube")
    val xml_rate = for {
      cube <- cubes
      if (cube \ "@currency").text == ticker.tag
    } yield (cube \ "@rate").text
    var rate: Double = 0
    if (xml_rate.size > 0) {
      rate = xml_rate(0).toDouble
    }

    Future(CentralBankRate(rate, ticker, EUR_TICKER))
  }
}