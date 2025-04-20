package fr.acinq.fc.app

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.router.Announcements
import fr.acinq.fc.app.Tools.DuplicateHandler
import fr.acinq.fc.app.db.{Blocking, HostedUpdatesDb, PreimagesDb, Updates}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.BitVector


class HostedUpdatesDbSpec extends AnyFunSuite {
  def sig: ByteVector64 = Crypto.sign(randomBytes32, randomKey)
  val a: Crypto.PrivateKey = randomKey
  val b: Crypto.PrivateKey = generatePubkeyHigherThan(a)
  val c: Crypto.PrivateKey = randomKey
  val d: Crypto.PrivateKey = generatePubkeyHigherThan(c)

  private def generatePubkeyHigherThan(priv: PrivateKey) = {
    var res = priv
    while (!Announcements.isNode1(priv.publicKey, res.publicKey)) res = randomKey
    res
  }

  test("Inserting preimages") {
    HCTestUtils.resetEntireDatabase(HCTestUtils.config.db)
    val pdb = new PreimagesDb(HCTestUtils.config.db)
    val preimage = randomBytes32

    val dh = new DuplicateHandler[ByteVector32] {
      def insert(data: ByteVector32): Boolean = {
        val hash = Crypto.sha256(preimage)
        pdb.addPreimage(hash, data)
      }
    }

    assert(dh.execute(preimage).isSuccess)
    assert(dh.execute(preimage).isFailure)

    assert(pdb.findByHash(randomBytes32).isEmpty)
    assert(pdb.findByHash(Crypto.sha256(preimage)).contains(preimage))
  }
}
