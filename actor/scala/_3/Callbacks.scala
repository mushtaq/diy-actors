package _3

import AccountActor.*
import common.Cancellable
import common.RichFuture.block

import java.util.concurrent.Executors
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}

trait Context:
  def executionContext: ExecutionContext
  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable
  def stop(): Unit

object Context:
  class Impl extends Context:
    val executorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
    override val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

    override def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
      val future = executorService.schedule[Unit](() => action, delay.length, delay.unit)
      () => future.cancel(true)

    def stop(): Unit = executorService.shutdown()

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
  def ask[R](f: Promise[R] => T): Future[R]

object ActorRef:
  class Impl[T](actor: Actor[T], context: Context) extends ActorRef[T]:
    def send(message: T): Future[Unit] =
      Future(actor.receive(message))(using context.executionContext)
    def ask[R](f: Promise[R] => T): Future[R] =
      val p = Promise[R]()
      send(f(p))
      p.future

//-----------------------------------------------------------------------------------------
object AccountActor:
  sealed trait Msg
  case class GetBalance(response: Promise[Int]) extends Msg
  case class Deposit(value: Int)                extends Msg
  case class DepositViaCallback(value: Int)     extends Msg
  case class Stop()                             extends Msg

class AccountActor(using Context) extends Actor[Msg]:
  private var balance = 0

//  given ExecutionContext = ExecutionContext.Implicits.global
  given ExecutionContext = context.executionContext

  override def receive(message: Msg): Unit = message match
    case GetBalance(response) =>
      response.success(balance)
    case Deposit(value) =>
//      println(Thread.currentThread())
      balance += value
    case DepositViaCallback(value) =>
      Future:
//        println(Thread.currentThread())
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

  (1 to 1000)
    .flatMap(* =>
      List(
        Future(actorRef.send(Deposit(1))),
        Future(actorRef.send(DepositViaCallback(1)))
      )
    )
    .foreach(_.block().block())

  val result: Future[Int] = actorRef.ask(p => GetBalance(p))

  println(result.block())

  actorRef.send(Stop())

  globalExecutor.shutdown()
