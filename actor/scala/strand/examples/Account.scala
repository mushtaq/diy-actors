package strand.examples

import common.ExternalService
import common.RichFuture.block
import strand.lib.{Strand, StrandSystem}

import scala.concurrent.Future

class Account(externalService: ExternalService)(using strand: Strand):
  import strand.async

  private var balance = 0

  def getBalance: Future[Int] = async:
    balance

  def deposit(x: Int): Future[Unit] = async:
    balance += x

//===========================================================================================
@main def accountMain(): Unit =
  val system = StrandSystem()
  import system.given

  val account = system.spawn(Account(ExternalService()))

  println(account.getBalance.block())

  (1 to 1000)
    .map: * =>
      Future:
        account.deposit(1)
    .foreach(_.block().block())

  // Read the current balance
  val result = account.getBalance.block()

  println(s"accResult = $result")

  system.stop()
