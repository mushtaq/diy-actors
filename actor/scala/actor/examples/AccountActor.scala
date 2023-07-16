package actor.examples

import actor.examples.AccountActor.{Deposit, GetBalance, Msg}
import actor.lib.{Actor, ActorSystem, Context}
import common.RichFuture.block

import scala.async.Async.*
import scala.concurrent.{Future, Promise}

//-----------------------------------------------------------------------------------------
object AccountActor:
  sealed trait Msg
  case class GetBalance(response: Promise[Int])           extends Msg
  case class Deposit(value: Int, response: Promise[Unit]) extends Msg

//-----------------------------------------------------------------------------------------
class AccountActor(using Context[Msg]) extends Actor[Msg]:
  private var balance = 0
  override def receive(message: Msg): Unit = message match
    case GetBalance(response) =>
      response.trySuccess(balance)
    case Deposit(value, response) =>
      balance += value
      response.trySuccess(())

//=========================================================================================
@main
def accountMain(): Unit =
  val system       = ActorSystem()
  val accountActor = system.spawn(AccountActor()).block()
  println(accountActor.ask(p => GetBalance(p)).block())

  (1 to 10000)
    .map(* => accountActor.ask(Deposit(1, _)))
    .foreach(_.block())

  val result = accountActor.ask(p => GetBalance(p)).block()
  println(result)

  system.stop()

@main
def lostUpdates(): Unit =
  val system = ActorSystem()
  import system.given

  val accountActor = new AccountActor(using null)

  (1 to 10000)
    .map(* => Future(accountActor.receive(Deposit(1, Promise()))))
    .map(_.block())

//    .map(f => f.map(* => accountActor.receive(Deposit(1, Promise()))))

  val p = Promise[Int]()
  accountActor.receive(GetBalance(p))
  println(p.future.block())
