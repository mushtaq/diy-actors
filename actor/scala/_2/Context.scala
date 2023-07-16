package _2

import AccountActor.*
import common.RichFuture.block

import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.{ExecutionContext, Future}

trait Context:
  def executionContext: ExecutionContext
  def stop(): Unit

object Context:
  class Impl extends Context:
    val executorService: ExecutorService            = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
    override val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)
    def stop(): Unit                                = executorService.shutdown()

//-----------------------------------------------------------------------------------------
trait Actor[T](using protected val context: Context):
  def receive(message: T): Unit

object ActorSystem:
  def spawn[T](actorFactory: Context ?=> Actor[T]): ActorRef[T] =
    val context = Context.Impl()
    val actor   = actorFactory(using context)
    ActorRef.Impl(actor, context)

trait ActorRef[T]:
  def send(message: T): Future[Unit]

object ActorRef:
  class Impl[T](actor: Actor[T], context: Context) extends ActorRef[T]:
    def send(message: T): Future[Unit] = Future(actor.receive(message))(using context.executionContext)

//-----------------------------------------------------------------------------------------
object AccountActor:
  sealed trait Msg
  case class GetBalance()        extends Msg
  case class Deposit(value: Int) extends Msg
  case class Stop()              extends Msg

class AccountActor(using Context) extends Actor[Msg]:
  private var balance = 0
  override def receive(message: Msg): Unit = message match
    case GetBalance() =>
      println(balance)
    case Deposit(value) =>
      println(Thread.currentThread())
      balance += value
    case Stop() =>
      // cleanup resources
      context.stop()

//-----------------------------------------------------------------------------------------
@main
def run1(): Unit =
  val globalExecutor     = Executors.newVirtualThreadPerTaskExecutor()
  given ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)

  val actorRef = ActorSystem.spawn(new AccountActor)

  (1 to 10000)
    .map(* => Future(actorRef.send(Deposit(1))).flatten)
    .foreach(_.block())

  actorRef.send(GetBalance())
  actorRef.send(Stop())

  globalExecutor.shutdown()

//-----------------------------------------------------------------------------------------
@main
def run2(): Unit =
  val actorRef = ActorSystem.spawn(new AccountActor)
  actorRef.send(GetBalance())
  actorRef.send(Stop())
