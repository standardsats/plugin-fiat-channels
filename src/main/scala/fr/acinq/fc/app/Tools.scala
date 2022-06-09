package fr.acinq.fc.app

import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, LexicographicalOrdering, Protocol, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.io.Peer.OutgoingMessage
import fr.acinq.eclair.io.PeerConnected
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.internal.channel.version3.FCProtocolCodecs
import fr.acinq.eclair.wire.protocol._
import fr.acinq.fc.app.channel.HostedCommitments
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import org.postgresql.util.PSQLException
import scodec.bits.ByteVector
import slick.jdbc.PostgresProfile

import java.io.{ByteArrayInputStream, File}
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try


object Tools {
  def none: PartialFunction[Any, Unit] = { case _ => }

  case object DuplicateShortId extends Throwable("Duplicate ShortId is not allowed here")

  abstract class DuplicateHandler[T] { self =>
    def execute(data: T): Try[Boolean] = Try(self insert data) recover {
      case dup: PSQLException if "23505" == dup.getSQLState => throw DuplicateShortId
      case otherError: Throwable => throw otherError
    }

    def insert(data: T): Boolean
  }

  def makePHCAnnouncementSignature(nodeParams: NodeParams, cs: HostedCommitments, shortChannelId: ShortChannelId, wantsReply: Boolean): AnnouncementSignature = {
    val witness = Announcements.generateChannelAnnouncementWitness(nodeParams.chainHash, shortChannelId, nodeParams.nodeId, cs.remoteNodeId, nodeParams.nodeId, cs.remoteNodeId, Features.empty)
    AnnouncementSignature(nodeParams.nodeKeyManager.signChannelAnnouncement(witness), wantsReply)
  }

  def makePHCAnnouncement(nodeParams: NodeParams, ls: AnnouncementSignature, rs: AnnouncementSignature, shortChannelId: ShortChannelId, remoteNodeId: Crypto.PublicKey): ChannelAnnouncement =
    Announcements.makeChannelAnnouncement(nodeParams.chainHash, shortChannelId, nodeParams.nodeId, remoteNodeId, nodeParams.nodeId, remoteNodeId, ls.nodeSignature, rs.nodeSignature, ls.nodeSignature, rs.nodeSignature)

  // HC ids derivation

  def hostedNodesCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector, ticker: Ticker): ByteVector32 = {
    val nodesCombined = hostedNodesCombined(pubkey1, pubkey2)
    val tickerBytes = ticker.tag.getBytes(StandardCharsets.UTF_8)
    Crypto.sha256(nodesCombined ++ ByteVector(tickerBytes))
  }

  def hostedShortChanId(pubkey1: ByteVector, pubkey2: ByteVector, ticker: Ticker): ShortChannelId = {
    val tickerBytes = ticker.tag.getBytes(StandardCharsets.UTF_8)
    val hash = Crypto.sha256(hostedNodesCombined(pubkey1, pubkey2) ++ ByteVector(tickerBytes))
    val stream = new ByteArrayInputStream(hash.toArray)
    def getChunk: Long = Protocol.uint64(stream, ByteOrder.BIG_ENDIAN)
    ShortChannelId(List.fill(8)(getChunk).sum)
  }
}

trait PeerConnectedWrap {
  def sendHasChannelIdMsg(message: HasChannelId): Unit
  def sendHostedChannelMsg(message: HostedChannelMessage): Unit
  def sendRoutingMsg(message: AnnouncementMessage): Unit
  def sendUnknownMsg(message: UnknownMessage): Unit
  def remoteIp: Array[Byte]
  def info: PeerConnected
}

case class PeerConnectedWrapNormal(info: PeerConnected) extends PeerConnectedWrap { me =>
  def sendHasChannelIdMsg(message: HasChannelId): Unit = me sendUnknownMsg FCProtocolCodecs.toUnknownHasChanIdMessage(message)
  def sendHostedChannelMsg(message: HostedChannelMessage): Unit = me sendUnknownMsg FCProtocolCodecs.toUnknownHostedMessage(message)
  def sendRoutingMsg(message: AnnouncementMessage): Unit = me sendUnknownMsg FCProtocolCodecs.toUnknownAnnounceMessage(message, isGossip = true)
  def sendUnknownMsg(message: UnknownMessage): Unit = info.peer ! OutgoingMessage(message, info.connectionInfo.peerConnection)
  lazy val remoteIp: Array[Byte] = info.connectionInfo.address.host.getBytes
}


class Config(datadir: File) {
  implicit val colorReader: ValueReader[Color] = ValueReader.relative { source =>
    Color(source.getInt("r").toByte, source.getInt("g").toByte, source.getInt("b").toByte)
  }

  val resourcesDir: File = new File(datadir, "/plugin-resources/hosted-channels/")

  val config: TypesafeConfig = ConfigFactory parseFile new File(resourcesDir, "fc.conf")

  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)

  val vals: Vals = config.as[Vals]("config.vals")

  lazy val brandingMessage: HostedChannelBranding =
    HostedChannelBranding(vals.branding.color, pngIcon = Try {
      val pngIconFile = new File(resourcesDir, vals.branding.logo)
      ByteVector view Files.readAllBytes(Paths get pngIconFile.getAbsolutePath)
    }.toOption, vals.branding.contactInfo)
}


case class FCParams(feeBaseMsat: Long, feeProportionalMillionths: Long, cltvDeltaBlocks: Int, channelCapacityMsat: Long, htlcMinimumMsat: Long, maxAcceptedHtlcs: Int, isResizable: Boolean) {
  def lastUpdateDiffers(u: ChannelUpdate): Boolean = u.cltvExpiryDelta.toInt != cltvDeltaBlocks || u.htlcMinimumMsat != htlcMinimum || u.feeBaseMsat != feeBase || u.feeProportionalMillionths != feeProportionalMillionths

  def initMsg(rate: MilliSatoshi, ticker: Ticker): InitHostedChannel = InitHostedChannel(UInt64(channelCapacityMsat), htlcMinimum, maxAcceptedHtlcs, channelCapacityMsat.msat, initialClientBalanceMsat = 0L.msat, initialRate = rate, ticker = ticker, channelFeatures)

  lazy val channelFeatures: List[Int] = if (isResizable) List(FCFeature.mandatory, ResizeableFCFeature.mandatory) else List(FCFeature.mandatory)

  lazy val htlcMinimum: MilliSatoshi = htlcMinimumMsat.msat

  lazy val feeBase: MilliSatoshi = feeBaseMsat.msat
}

case class Branding(logo: String, color: Color, contactInfo: String, enabled: Boolean)

case class PHCConfig(maxPerNode: Long, minNormalChans: Long, maxSyncSendsPerIpPerMinute: Int, minCapacityMsat: Long, maxCapacityMsat: Long) {
  val minCapacity: MilliSatoshi = MilliSatoshi(minCapacityMsat)

  val maxCapacity: MilliSatoshi = MilliSatoshi(maxCapacityMsat)
}

case class Vals(hcParams: FCParams, maxNewChansPerIpPerHour: Int, maxPreimageRequestsPerIpPerMinute: Int, branding: Branding, phcConfig: PHCConfig)