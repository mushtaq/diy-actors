package _4

import AccountActor.*
import common.Cancellable
import common.RichFuture.block

import java.util.concurrent.Executors
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}

trait Context[T]:
  def executionContext: ExecutionContext
  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable
  def self: ActorRef[T]
  def stop(): Unit

object Context:
  class Impl[T](actorFactory: Context[T] ?=> Actor[T]) extends Context[T]:
    val executorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
    override val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

    val actor: Actor[T]            = actorFactory(using this)
    override def self: ActorRef[T] = ActorRef.Impl(actor, this)

    override def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
      val future = executorService.schedule[Unit](() => action, delay.length, delay.unit)
      () => future.cancel(false)

    def stop(): Unit = executorService.shutdown()

//-----------------------------------------------------------------------------------------
trait Actor[T](using protected val context: Context[T]):
  def receive(message: T): Unit

object ActorSystem:
  def spawn[T](actorFactory: Context[T] ?=> Actor[T]): ActorRef[T] =
    Context.Impl[T](actorFactory).self

trait ActorRef[T]:
  def send(message: T): Future[Unit]
  def ask[R](f: Promise[R] => T): Future[R]

object ActorRef:
  class Impl[T](actor: Actor[T], context: Context[T]) extends ActorRef[T]:
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

class AccountActor(using Context[Msg]) extends Actor[Msg]:
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

  val result = actorRef.ask(p => GetBalance(p)).block()

  println(result)

  actorRef.send(Stop())

  globalExecutor.shutdown()
