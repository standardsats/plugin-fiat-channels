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
import grizzled.slf4j.Logging
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

//ecb stuff
import scala.xml.XML

case class CentralBankRate(rate: Double, ticker: String, asset: String)

trait CentralBankSource {
  def askRates: Future[CentralBankRate]
}

class EcbSource(ticker: String = "USD", implicit val system: ActorSystem) extends CentralBankSource {
  val http = Http(system)
  val ecbUri = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

  def askRates: Future[CentralBankRate] = {
    if(ticker == "EUR")
      return Future(CentralBankRate(1, ticker, "EUR"))

    val xml = XML.load(ecbUri)
    val cubes = (xml \\ "Cube")
    val xml_rate = for {
      cube <- cubes
      if (cube \ "@currency").text == ticker
    } yield (cube \ "@rate").text
    var rate: Double = 0
    if (xml_rate.size > 0) {
      rate = xml_rate(0).toDouble
    }

    Future(CentralBankRate(rate, ticker, "EUR"))
  }
}