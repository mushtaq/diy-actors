package strand.examples

import common.ExternalService
import common.RichFuture.block
import strand.lib.{Strand, StrandSystem}

import scala.concurrent.{ExecutionContext, Future}

class Account(externalService: ExternalService)(using strand: Strand):
  import strand.async

  private var balance = 0

  def getBalance: Future[Int] = async:
    balance

  def deposit(x: Int): Future[Unit] = async:
    balance += x
    externalService.ioCall().await

//===========================================================================================
@main def accountMain(): Unit =
  val system             = StrandSystem()
  given ExecutionContext = system.executionContext

  val account = system.spawn(Account(ExternalService())).block()
  println(account.getBalance.block())

  (1 to 10000)
    .map: * =>
      Future:
        account.deposit(1)
    .foreach(_.block().block())

  val result = account.getBalance.block()
  println(result)

  system.stop()
