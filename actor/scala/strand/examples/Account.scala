package strand.examples

import common.RichFuture.block
import strand.lib.{Strand, StrandSystem}

import scala.concurrent.{ExecutionContext, Future}

class Account(using strand: Strand):
  import strand.given

  private var balance = 0

  def getBalance: Future[Int] = Future:
    balance

  def deposit(x: Int): Future[Unit] = Future:
    balance += x

//===========================================================================================
@main def accountMain(): Unit =
  val system             = StrandSystem()
  given ExecutionContext = system.executionContext

  val account        = system.spawn(Account()).block()
  val initialBalance = account.getBalance.block()
  println(initialBalance)

  (1 to 10000)
    .map: * =>
      Future:
        account.deposit(1)
    .foreach(_.block().block())

  val finalBalance = account.getBalance.block()
  println(finalBalance)

  system.stop()
