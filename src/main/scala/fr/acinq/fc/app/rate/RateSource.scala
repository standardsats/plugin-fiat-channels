package fr.acinq.fc.app.rate

import akka.actor.{Actor, ActorSystem}
import akka.actor.Status
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
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

case class FiatRate(rate: Double)

trait RateSource {
  def askRates: Future[FiatRate]
}

class BitfinexSource(implicit system: ActorSystem) extends RateSource {
  val http = Http(system)

  def askRates: Future[FiatRate] = {
    for {
      res <- http.singleRequest(HttpRequest(uri = "https://api-pub.bitfinex.com/v2/ticker/tBTCUSD"))
      body <- res match {
        case HttpResponse(StatusCodes.OK, headers, entity, _) => entity.dataBytes.runFold(ByteString(""))(_ ++ _)
        case resp @ HttpResponse(code, _, _, _) =>
          resp.discardEntityBytes()
          throw new RuntimeException("Request failed, response code: " + code)
      }
      values <- Unmarshal(body).to[List[Double]]
    } yield FiatRate(values.head)
  }
}

class BlockchainInfo24h(implicit system: ActorSystem) extends RateSource {
  val http = Http(system)

  def askRates: Future[FiatRate] = {
    for {
      res <- http.singleRequest(HttpRequest(uri = "https://blockchain.info/q/24hrprice"))
      body <- res match {
        case HttpResponse(StatusCodes.OK, headers, entity, _) => entity.dataBytes.runFold(ByteString(""))(_ ++ _)
        case resp @ HttpResponse(code, _, _, _) =>
          resp.discardEntityBytes()
          throw new RuntimeException("Request failed, response code: " + code)
      }
      value = body.decodeString(ByteString.UTF_8).toDouble
    } yield FiatRate(value)
  }
}

final case class BinanceResponse(price: String)

trait BinanceJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val binanceFormat = jsonFormat1(BinanceResponse)
}

class BinanceSource(implicit system: ActorSystem) extends RateSource with BinanceJsonSupport {
  val http = Http(system)

  def askRates: Future[FiatRate] = {
    for {
      res <- http.singleRequest(HttpRequest(uri = "https://api.binance.com/api/v3/avgPrice?symbol=BTCUSDT"))
      body <- res match {
        case HttpResponse(StatusCodes.OK, headers, entity, _) => entity.dataBytes.runFold(ByteString(""))(_ ++ _)
        case resp @ HttpResponse(code, _, _, _) =>
          resp.discardEntityBytes()
          throw new RuntimeException("Request failed, response code: " + code)
      }
      values <- Unmarshal(body).to[BinanceResponse]
    } yield FiatRate(values.price.toDouble)
  }
}

class BinanceSourceModified(predicate: Double => Double, implicit val system: ActorSystem) extends RateSource with BinanceJsonSupport {
  val http = Http(system)

  def askRates: Future[FiatRate] = {
    for {
      res <- http.singleRequest(HttpRequest(uri = "https://api.binance.com/api/v3/avgPrice?symbol=BTCUSDT"))
      body <- res match {
        case HttpResponse(StatusCodes.OK, headers, entity, _) => entity.dataBytes.runFold(ByteString(""))(_ ++ _)
        case resp @ HttpResponse(code, _, _, _) =>
          resp.discardEntityBytes()
          throw new RuntimeException("Request failed, response code: " + code)
      }
      values <- Unmarshal(body).to[BinanceResponse]
    } yield FiatRate(predicate(values.price.toDouble))
  }
}

class BlockchainInfo24hModified(predicate: Double => Double, implicit val system: ActorSystem) extends RateSource {
  val http = Http(system)

  def askRates: Future[FiatRate] = {
    for {
      res <- http.singleRequest(HttpRequest(uri = "https://blockchain.info/q/24hrprice"))
      body <- res match {
        case HttpResponse(StatusCodes.OK, headers, entity, _) => entity.dataBytes.runFold(ByteString(""))(_ ++ _)
        case resp @ HttpResponse(code, _, _, _) =>
          resp.discardEntityBytes()
          throw new RuntimeException("Request failed, response code: " + code)
      }
      value = body.decodeString(ByteString.UTF_8).toDouble
    } yield FiatRate(predicate(value))
  }
}