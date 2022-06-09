package fr.acinq.fc.app.db

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.wire.internal.channel.version3.FiatChannelCodecs.HC_DATA_ESTABLISHED_Codec
import fr.acinq.fc.app.{Ticker, Tools}
import fr.acinq.fc.app.channel.HC_DATA_ESTABLISHED
import fr.acinq.fc.app.db.Blocking.ByteArray
import scodec.bits.{BitVector, ByteVector}
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._


class HostedChannelsDb(db: PostgresProfile.backend.Database) {
  private def decode(data: ByteArray) = HC_DATA_ESTABLISHED_Codec.decode(BitVector view data).require.value

  def addNewChannel(data: HC_DATA_ESTABLISHED): Boolean = {
    val remoteNodeId = data.commitments.remoteNodeId
    val localNodeId = data.commitments.localNodeId
    val ticker = data.commitments.lastCrossSignedState.initHostedChannel.ticker
    val channelId: ByteVector32 = Tools.hostedChanId(localNodeId.value, remoteNodeId.value, ticker)
    Blocking.txWrite(Channels.insertCompiled += Tuple10(remoteNodeId.value.toArray, channelId.toArray, data.channelUpdate.shortChannelId.toLong,
      0, data.commitments.lastCrossSignedState.isHost, data.commitments.lastCrossSignedState.blockDay, ticker.tag, System.currentTimeMillis,
      HC_DATA_ESTABLISHED_Codec.encode(data).require.toByteArray, Array.emptyByteArray), db) > 0
  }

  def updateOrAddNewChannel(data: HC_DATA_ESTABLISHED): Unit = {
    val encoded = HC_DATA_ESTABLISHED_Codec.encode(data).require.toByteArray
    val inFlightHtlcs = data.pendingHtlcs.size

    val updateTuple = (inFlightHtlcs, data.commitments.lastCrossSignedState.isHost, data.commitments.lastCrossSignedState.blockDay, encoded)
    val remoteNodeId = data.commitments.remoteNodeId
    val localNodeId = data.commitments.localNodeId
    val ticker = data.commitments.lastCrossSignedState.initHostedChannel.ticker
    val channelId: ByteVector32 = Tools.hostedChanId(localNodeId.value, remoteNodeId.value, ticker)

    if (Blocking.txWrite(Channels.findByRemoteNodeIdUpdatableCompiled(Tuple2(remoteNodeId.value.toArray, ticker.tag)).update(updateTuple), db) == 0)
      Blocking.txWrite(Channels.insertCompiled += Tuple10(remoteNodeId.value.toArray, channelId.toArray, data.channelUpdate.shortChannelId.toLong, inFlightHtlcs,
        data.commitments.lastCrossSignedState.isHost, data.commitments.lastCrossSignedState.blockDay, ticker.tag, System.currentTimeMillis,
        encoded, Array.emptyByteArray), db)
  }

  def updateSecretById(remoteNodeId: PublicKey, ticker: Ticker, secret: ByteVector): Boolean =
    Blocking.txWrite(Channels.findSecretUpdatableByRemoteNodeIdCompiled(remoteNodeId.value.toArray, ticker.tag).update(secret.toArray), db) > 0

  def getChannelByRemoteNodeId(remoteNodeId: PublicKey, ticker: Ticker): Option[HC_DATA_ESTABLISHED] = for {
    (_, _, _, data) <- Blocking.txRead(Channels.findByRemoteNodeIdUpdatableCompiled(remoteNodeId.value.toArray, ticker.tag).result.headOption, db)
  } yield decode(data)

  def getChannelByChanId(chanId: ByteVector32): Option[HC_DATA_ESTABLISHED] = for {
    (_, _, _, data) <- Blocking.txRead(Channels.findByChanIdUpdatableCompiled(chanId.toArray).result.headOption, db)
  } yield decode(data)

  def getChannelBySecret(secret: ByteVector): Option[HC_DATA_ESTABLISHED] = Blocking.txRead(Channels.findBySecretCompiled(secret.toArray).result.headOption, db).map(decode)

  def listHotChannels: Seq[HC_DATA_ESTABLISHED] = Blocking.txRead(Channels.listHotChannelsCompiled.result, db).map(decode)

  def listAllChannels: Seq[HC_DATA_ESTABLISHED] = Blocking.txRead(Channels.listAllChannelsCompiled.result, db).map(decode)

  def listClientChannels: Seq[HC_DATA_ESTABLISHED] = Blocking.txRead(Channels.listClientChannelsCompiled.result, db).map(decode)
}
