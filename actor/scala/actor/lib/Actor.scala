package actor.lib

import common.Cancellable

import java.util.concurrent.Executors
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}

trait ActorRef[T]:
  def send(message: T): Future[Unit]
  def ask[R](f: Promise[R] => T): Future[R]
  def stop(): Future[Unit]

//-----------------------------------------------------------------------------------------
object ActorRef:
  class Impl[T](actor: Actor[T])(using val context: Context[T]) extends ActorRef[T]:
    def send(message: T): Future[Unit] =
      Future(actor.receive(message))(using context.executionContext)

    def ask[R](f: Promise[R] => T): Future[R] =
      val p = Promise[R]()
      send(f(p))
      p.future

    def stop(): Future[Unit] = context.stop()

//===========================================================================================
abstract class Actor[T](using protected val context: Context[T]):
  given ExecutionContext = context.executionContext
  def receive(message: T): Unit

//===========================================================================================
trait Context[A]:
  def executionContext: ExecutionContext
  def spawn[T](actorFactory: Context[T] ?=> Actor[T]): ActorRef[T]
  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable
  def self: ActorRef[A]
  def children: Set[ActorRef[_]]
  def stop(): Future[Unit]

//-----------------------------------------------------------------------------------------
object Context:
  class Impl[A](actorFactory: Context[A] ?=> Actor[A], parent: Option[Impl[_]]) extends Context[A]:
    parentContext =>

    private var childContexts: Set[Impl[_]] = Set.empty
    def children: Set[ActorRef[_]]          = childContexts.map(_.self)

    //  private val strandExecutor = Executors.newScheduledThreadPool(1000, Thread.ofVirtual().factory())
    private val strandExecutor             = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
    val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(strandExecutor)

    private val actor: Actor[A]    = actorFactory(using parentContext)
    override val self: ActorRef[A] = ActorRef.Impl(actor)(using parentContext)

    given ExecutionContext = executionContext

    def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
      val future = strandExecutor.schedule[Unit](() => action, delay.length, delay.unit)
      () => future.cancel(true)

    def spawn[T](actorFactory: Context[T] ?=> Actor[T]): ActorRef[T] =
      val childContext = Impl[T](actorFactory, Some(parentContext))
      childContexts += childContext
      childContext.self

    def stop(): Future[Unit] =
      Future
        .sequence(childContexts.map(_.stop()))
        .flatMap(* => stopSelf())

    private def stopSelf(): Future[Unit] =
      strandExecutor.shutdown()
      parent match
        case Some(p) => p.remove(this)
        case None    => Future.unit

    private def remove(child: Impl[_]): Future[Unit] =
      Future(childContexts -= child)

//===========================================================================================
class ActorSystem:
  private val executorService            = Executors.newVirtualThreadPerTaskExecutor()
  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

  private val rootContext = Context.Impl[Unit](null, None)
  given ExecutionContext  = rootContext.executionContext

  def spawn[T](actorFactory: Context[T] ?=> Actor[T]): Future[ActorRef[T]] =
    Future:
      rootContext.spawn(actorFactory)

  def stop(): Future[Unit] =
    rootContext
      .stop()
      .map(_ => executorService.shutdown())
