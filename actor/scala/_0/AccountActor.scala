package _0

import Msg.*
import common.RichFuture.block

import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait Context:
  def executionContext: ExecutionContext
  def spawn[T](actorFactory: Context ?=> Actor[T]): ActorRef[T]

private object Context:
  class Impl extends Context:
    private var children: List[Impl] = Nil

    val executorService: ExecutorService   = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
    val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

    def spawn[T](actorFactory: Context ?=> Actor[T]): ActorRef[T] =
      val context = Impl()

      val actorRef = new ActorRef[T]:
        val actor: Actor[T] = actorFactory(using context)
        def send(message: T): Future[Unit] =
          Future(actor.receive(message))(using context.executionContext)

      Future(children ::= context)(using this.executionContext)
      actorRef

//-----------------------------------------------------------------------------------------
trait Actor[T](using protected val context: Context):
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
enum Msg:
  case GetBalance
  case Deposit(value: Int)

class AccountActor(using Context) extends Actor[Msg]:
  private var balance = 0
  override def receive(message: Msg): Unit = message match
    case GetBalance =>
      println(balance)
    case Deposit(value) =>
//      println(Thread.currentThread())
      balance += value

//-----------------------------------------------------------------------------------------
@main
def run1(): Unit =
  val globalExecutor     = Executors.newFixedThreadPool(1000)
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)
  given Context          = Context.Impl()

  val actorRef = ActorSystem.spawn(new AccountActor)

  (1 to 10000)
    .map(* => Future(actorRef.send(Deposit(1))).flatten)
    .foreach(_.block())

  actorRef.send(GetBalance)

  globalExecutor.shutdown()

@main
//show that each Actor creates a thread
def run2(): Unit =
  val globalExecutor     = Executors.newFixedThreadPool(1000)
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)
  given Context          = Context.Impl()

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
  given Context          = new Context.Impl

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
