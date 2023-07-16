package _1

import AccountActor.*
import common.RichFuture.block

import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

abstract class Actor[T]:
  def receive(message: T): Unit

object ActorSystem:
  def spawn[T](actorFactory: => Actor[T]): ActorRef[T] = new ActorRef[T]:
    val executorService: ExecutorService   = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
    val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

    val actor: Actor[T]                = actorFactory
    def send(message: T): Future[Unit] = Future(actor.receive(message))(using executionContext)

trait ActorRef[T]:
  def send(message: T): Future[Unit]

//-----------------------------------------------------------------------------------------
object AccountActor:
  sealed trait Msg
  case class GetBalance()        extends Msg
  case class Deposit(value: Int) extends Msg

class AccountActor extends Actor[Msg]:
  private var balance = 0
  override def receive(message: Msg): Unit = message match
    case GetBalance() =>
      println(balance)
    case Deposit(value) =>
      println(Thread.currentThread())
      balance += value

//-----------------------------------------------------------------------------------------
@main
def run0(): Unit =
  val globalExecutor     = Executors.newFixedThreadPool(1000)
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)

  val accountActor = new AccountActor()

  (1 to 10000)
    .map(* => Future(accountActor.receive(Deposit(1))))
    .foreach(_.block())

  accountActor.receive(GetBalance())

  globalExecutor.shutdown()

//-----------------------------------------------------------------------------------------
@main
def run1(): Unit =
  val globalExecutor     = Executors.newFixedThreadPool(1000)
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)

  val actorRef = ActorSystem.spawn(new AccountActor)

  (1 to 10000)
    .map(* => Future(actorRef.send(Deposit(1))).flatten)
    .foreach(_.block())

  actorRef.send(GetBalance())

  globalExecutor.shutdown()

@main
//show that each Actor creates a thread
def run2(): Unit =
  val globalExecutor     = Executors.newFixedThreadPool(1000)
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)

  (1 to 10)
    .map: * =>
      Future:
        val actorRef = ActorSystem.spawn(new AccountActor)
        actorRef.send(Deposit(1))
      .flatten
    .foreach(_.block())

  globalExecutor.shutdown()

@main
//show that one VT can run on multiple carrier threads (non-deterministic)
def run3(): Unit =
  val globalExecutor     = Executors.newFixedThreadPool(1000)
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)

  val actorRefs = (1 to 10).map: _ =>
    ActorSystem.spawn(new AccountActor)

  (1 to 10).foreach: x =>
    Future:
      Random
        .shuffle(actorRefs)
        .foreach: x =>
          Future:
            x.send(Deposit(1))

  Thread.sleep(1000)
  globalExecutor.shutdown()
