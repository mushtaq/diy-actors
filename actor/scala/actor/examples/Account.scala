package actor.examples

import actor.examples.Msg.*
import actor.lib.{Actor, ActorSystem, Context}
import common.RichFuture.block

import scala.concurrent.{ExecutionContext, Future, Promise}

//-----------------------------------------------------------------------------------------
enum Msg:
  case GetBalance(response: Promise[Int])
  case Deposit(value: Int, response: Promise[Unit])

//-----------------------------------------------------------------------------------------
class Account(using Context[Msg]) extends Actor[Msg]:
  private var balance = 0
  override def receive(message: Msg): Unit =
    message match
      case GetBalance(response) =>
        response.trySuccess(balance)
      case Deposit(value, response) =>
        balance += value
        response.trySuccess(())

//=========================================================================================
@main
def accountMain(): Unit =
  val system             = ActorSystem()
  given ExecutionContext = system.executionContext

  val account        = system.spawn(Account()).block()
  val initialBalance = account.ask(p => GetBalance(p))
  println(initialBalance.block())

  (1 to 10000)
    .map: * =>
      Future:
        account.ask(Deposit(1, _))
    .foreach(_.block().block())

  val finalBalance = account.ask(p => GetBalance(p))
  println(finalBalance.block())

  system.stop()

@main
def lostUpdates(): Unit =
  val system             = ActorSystem()
  given ExecutionContext = system.executionContext

  val accountActor = new Account(using null)

  (1 to 10000)
    .map(* => Future(accountActor.receive(Deposit(1, Promise()))))
    .map(_.block())

//    .map(f => f.map(* => accountActor.receive(Deposit(1, Promise()))))

  val p = Promise[Int]()
  accountActor.receive(GetBalance(p))
  println(p.future.block())
