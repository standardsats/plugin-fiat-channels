package fr.acinq.fc.app.network

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.RealShortChannelId
import fr.acinq.eclair.router.GraphWithBalanceEstimates
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.fc.app.PHCConfig

import scala.collection.immutable.SortedMap

// STATE

sealed trait HostedSyncState

case object WAIT_FOR_ROUTER_DATA extends HostedSyncState

case object WAIT_FOR_PHC_SYNC extends HostedSyncState

case object DOING_PHC_SYNC extends HostedSyncState

case object OPERATIONAL extends HostedSyncState

// DATA

sealed trait HostedSyncData {
  def phcNetwork: PHCNetwork
}

case class WaitForNormalNetworkData(phcNetwork: PHCNetwork) extends HostedSyncData

case class OperationalData(phcNetwork: PHCNetwork, phcGossip: CollectedGossip, syncNodeId: Option[PublicKey], normalChannels: SortedMap[RealShortChannelId, PublicChannel], normalGraph: GraphWithBalanceEstimates) extends HostedSyncData {

  def tooFewNormalChans(nodeId1: PublicKey, nodeId2: PublicKey, phcConfig: PHCConfig): Option[PublicKey] = tooFewNormalChans(nodeId1, phcConfig) orElse tooFewNormalChans(nodeId2, phcConfig)

  def tooFewNormalChans(nodeId: PublicKey, phcConfig: PHCConfig): Option[PublicKey] = if (normalGraph.getIncomingEdgesOf(nodeId).size < phcConfig.minNormalChans) Some(nodeId) else None
}