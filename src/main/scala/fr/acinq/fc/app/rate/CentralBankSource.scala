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

case class CentralBankRate(rate: Double)

trait CentralBankSource {
  def askRates: Future[CentralBankRate]
}

class EcbSource(predicate: Double => Double, ticker: String = "USD", implicit val system: ActorSystem) extends CentralBankSource {
  val http = Http(system)
  val ecbUri = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

  def askRates: Future[CentralBankRate] = {
    val xml = XML.load(ecbUri)
    println(xml.getClass())
    println("Number of elements is " + (xml \\ "Cube").length)
    //xml.foreach(println(_))

    val firstTitle = (xml \\ "Cube" \ "currency").text
    //println(firstTitle)
    //(xml \ "Cube").foreach(println(_.attributes.get("foo").getOrElse("N/A")))
    //for (a <- xml.attributes) println(s"key: ${a.key}, value: ${a.value}")
    //println(xml.attributes.asAttrMap)

    for (n <- xml.child) println(n.attributes)

    val strings = for {
      e <- xml.child
      if e.label == "USD"
    } yield e.text

    println(strings)
    /*val definitionMap = (Map[String, String]() /: (xml \ "Cube")) { (map , defNode) =>
      val word = (defNode \ "Cube").text.toString()
      val description = (defNode \ "currency").text.toString()
      map + (word -> description)
    }
    println(definitionMap)*/
    //val temp = (xml \\ "Cube" \\ "time" \ "@temp")
    Future(CentralBankRate(1.0))
  }
}