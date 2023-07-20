package _5

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
  def spawn[R](actorFactory: Context[R] ?=> Actor[R]): ActorRef[R]
  def stop(): Unit

object Context:
  class Impl[T](actorFactory: Context[T] ?=> Actor[T], parent: Option[Impl[_]]) extends Context[T]:
    parentContext =>

    private var childContexts: Set[Impl[_]] = Set.empty

    val executorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
    override val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

    val actor: Actor[T]            = actorFactory(using this)
    override val self: ActorRef[T] = ActorRef.Impl(actor, this)

    override def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
      val future = executorService.schedule[Unit](() => action, delay.length, delay.unit)
      () => future.cancel(false)

    def spawn[R](actorFactory: Context[R] ?=> Actor[R]): ActorRef[R] =
      val childContext = Impl[R](actorFactory, Some(parentContext))
      childContexts += childContext
      childContext.self

    given ExecutionContext = executionContext

    def stop(): Unit = Future:
      childContexts.foreach(_.stop())
      executorService.shutdown()
      parent.foreach(_.remove(this))

    private def remove(child: Impl[_]): Unit = Future:
      childContexts -= child

//-----------------------------------------------------------------------------------------
trait Actor[T](using protected val context: Context[T]):
  def receive(message: T): Unit

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

class ActorSystem:
  private val rootContext = Context.Impl(null, None)
  given ExecutionContext  = rootContext.executionContext

  def spawn[T](actorFactory: Context[T] ?=> Actor[T]): Future[ActorRef[T]] =
    Future:
      rootContext.spawn(actorFactory)

  def stop(): Unit =
    rootContext.stop()

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

  val actorSystem = ActorSystem()

  val actorRef = actorSystem.spawn(new AccountActor).block()

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

//-----------------------------------------------------------------------------------------
@main
def run2(): Unit =
  val actorSystem = ActorSystem()

  (1 to 100)
    .foreach: * =>
      val actorRef = actorSystem.spawn(new AccountActor).block()
      actorRef.send(Deposit(1)).block()

  actorSystem.stop()
