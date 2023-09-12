package actor.lib

import common.Cancellable

import java.util.concurrent.{Executors, ScheduledExecutorService}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}

trait ActorRef[T]:
  def send(message: T): Future[Unit]
  def ask[R](f: Promise[R] => T): Future[R]

//-----------------------------------------------------------------------------------------
object ActorRef:
  class Impl[T](actor: Actor[T])(using val context: Context[T]) extends ActorRef[T]:
    def send(message: T): Future[Unit] =
      Future(actor.receive(message))(using context.executionContext)

    def ask[R](f: Promise[R] => T): Future[R] =
      val p = Promise[R]()
      send(f(p))
      p.future

//===========================================================================================
abstract class Actor[T](using protected val context: Context[T]):
  given ExecutionContext = context.executionContext
  def receive(message: T): Unit

//===========================================================================================
trait Context[T]:
  def executionContext: ExecutionContext
  def spawn[R](actorFactory: Context[R] ?=> Actor[R]): ActorRef[R]
  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable
  def self: ActorRef[T]
  def stop(): Unit

//-----------------------------------------------------------------------------------------
object Context:
  class Impl[T](actorFactory: Context[T] ?=> Actor[T], parent: Option[Impl[_]]) extends Context[T]:
    private var childContexts: Set[Impl[_]] = Set.empty

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
    val executionContext: ExecutionContext = ExecutionContext.fromExecutor(executor)

    private val actor: Actor[T]    = actorFactory(using this)
    override val self: ActorRef[T] = ActorRef.Impl(actor)(using this)

    given ExecutionContext = executionContext

    def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
      val future = executor.schedule[Unit](() => action, delay.length, delay.unit)
      () => future.cancel(false)

    def spawn[R](actorFactory: Context[R] ?=> Actor[R]): ActorRef[R] =
      val childContext = Impl[R](actorFactory, Some(this))
      childContexts += childContext
      childContext.self

    def stop(): Unit = Future:
      childContexts.foreach(_.stop())
      executor.shutdown()
      parent.foreach(_.remove(this))

    private def remove(child: Impl[_]): Unit = Future:
      childContexts -= child

//===========================================================================================
class ActorSystem:
  private val executorService            = Executors.newVirtualThreadPerTaskExecutor()
  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

  private val rootContext = Context.Impl(null, None)

  def spawn[T](actorFactory: Context[T] ?=> Actor[T]): Future[ActorRef[T]] =
    Future(
      rootContext.spawn(actorFactory)
    )(using rootContext.executionContext)

  def stop(): Unit =
    rootContext.stop()
    executorService.shutdown()
