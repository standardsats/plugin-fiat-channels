package fr.acinq.fc.app.rate

import akka.actor.Actor
import akka.actor.Status
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.util.ByteString
import fr.acinq.eclair
import fr.acinq.eclair._
import grizzled.slf4j.Logging
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object RateOracle {
  case object TickUpdateRate { val label = "TickUpdateRate" }
}

class RateOracle(kit: eclair.Kit) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, RateOracle.TickUpdateRate)

  var lastRate: MilliSatoshi = 0L.msat

  implicit val system = context.system
  val http = Http(system)

  // case class RatesArray(name: String)
  // implicit val petFormat = jsonFormat1(Pet)

  override def receive: Receive = {
    case RateOracle.TickUpdateRate =>
      logger.info("Updating current fiat rate")
      queryRates

    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
        Unmarshal(body).to[List[Float]].pipeTo(self)
      }

    case values: List[_] =>
      logger.info("Got response, body: " + values)

    case resp @ HttpResponse(code, _, _, _) =>
      logger.info("Request failed, response code: " + code)
      resp.discardEntityBytes()

    case Status.Failure(e) =>
      logger.error("Request failed: " + e)
  }

  private def queryRates: Unit = {
    http.singleRequest(HttpRequest(uri = "https://api-pub.bitfinex.com/v2/ticker/tBTCUSD"))
      .pipeTo(self)
  }

}
