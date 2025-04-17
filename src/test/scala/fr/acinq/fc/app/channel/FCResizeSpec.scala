package fr.acinq.fc.app.channel

import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_FULFILL_HTLC, CMD_SIGN, NORMAL, OFFLINE, RES_ADD_SETTLED}
import fr.acinq.fc.app.{HCTestUtils, InvokeHostedChannel, LastCrossSignedState, ResizeChannel, StateUpdate, Worker}
import org.scalatest.Outcome
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.fc.app.Ticker.USD_TICKER
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class FCResizeSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with FCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Resize without in-flight HTLCs") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.capacity == Satoshi(10000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(0))
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, USD_TICKER, Satoshi(50000000))
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.capacity == Satoshi(50000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.capacity == Satoshi(50000000))
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(Satoshi(MilliSatoshi(9000000000), f, currentBlockHeight)
    val (preimage2, alice2bobUpdateAdd2) = addHtlcFromAliceToBob(MilliSatoshi(9000000000), f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd1.id, preimage1, f)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd2.id, preimage2, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(32000000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(18000000000))
  }

  test("Resize with HTLCs in-flight") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(MilliSatoshi(5000000000), f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(MilliSatoshi(2000000000), f, currentBlockHeight)
    val (preimage2, bob2aliceUpdateAdd2) = addHtlcFromBob2Alice(MilliSatoshi(2000000000), f)
    alice ! CMD_FULFILL_HTLC(bob2aliceUpdateAdd2.id, preimage2)
    alice ! CMD_SIGN(None)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, USD_TICKER, Satoshi(20000000))
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd1.id, preimage1)
    bob ! CMD_SIGN(None)
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(15000000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(5000000000))
    bobRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    val (preimage3, alice2bobUpdateAdd3) = addHtlcFromAliceToBob(MilliSatoshi(1000000000), f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd3.id, preimage3, f)
  }

  test("Host does not get resize proposal before restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(MilliSatoshi(5000000000), f, currentBlockHeight)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, USD_TICKER, Satoshi(20000000))
    bob2alice.expectMsgType[ResizeChannel] // Alice does not get it
    bob2alice.expectMsgType[StateUpdate]

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(15000000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(5000000000))
  }

  test("Host applies resize proposal before restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(MilliSatoshi(5000000000), f, currentBlockHeight)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, USD_TICKER, Satoshi(20000000))
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice2bob.expectMsgType[StateUpdate] // Bob does not get it
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(15000000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(5000000000))
  }

  test("Host gets resize without update, intertwined with add") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, USD_TICKER, Satoshi(20000000))
    alice ! bob2alice.expectMsgType[ResizeChannel]
    bob2alice.expectMsgType[StateUpdate] // Alice does not get it
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ResizeChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    val (preimage, cmd_add, _) = makeCmdAdd(MilliSatoshi(100000), bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add
    alice ! CMD_SIGN(None)

    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[ResizeChannel]

    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    val aliceAdd = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    fulfillAliceHtlcByBob(aliceAdd.id, preimage, f)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("Host gets resize without update with payment in-flight") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(MilliSatoshi(5000000000), f, currentBlockHeight)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, USD_TICKER, Satoshi(20000000))
    alice ! bob2alice.expectMsgType[ResizeChannel]
    bob2alice.expectMsgType[StateUpdate] // Alice does not get it
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    alice ! Worker.HCPeerDisconnected
    bob ! Worker.HCPeerDisconnected
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ResizeChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[ResizeChannel]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd0.id, preimage0)
    bob ! CMD_SIGN(None)
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]

    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(15000000000))
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == MilliSatoshi(5000000000))
  }
}
