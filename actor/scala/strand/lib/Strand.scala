package strand.lib

import common.Cancellable

import java.util.concurrent.Executors
import scala.async.Async
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

//-----------------------------------------------------------------------------------------
class Context private[lib] ():
  inline def async[T](inline x: T): Future[T]     = Async.async(x)
  extension [T](x: Future[T]) inline def await: T = Async.await(x)

  private var children: List[Context] = Nil

  private val strandExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
  given ExecutionContext     = ExecutionContext.fromExecutorService(strandExecutor)

  def spawn[R](strandFactory: Context ?=> R): R =
    val ctx = Context()
    Future:
      children ::= ctx
    strandFactory(using ctx)

  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
    val future = strandExecutor.schedule[Unit](() => action, delay.length, delay.unit)
    () => future.cancel(false)

  def stop(): Future[Unit] =
    Future
      .traverse(children)(_.stop())
      .map(_ => strandExecutor.shutdown())

//===========================================================================================
class StrandSystem:
  private val globalExecutor = Executors.newVirtualThreadPerTaskExecutor()
  given ExecutionContext     = ExecutionContext.fromExecutorService(globalExecutor)

  private val context: Context = Context()
  export context.{spawn, schedule}

  def stop(): Future[Unit]           = context.stop().map(_ => globalExecutor.shutdown())
  def future[T](op: => T): Future[T] = Future(op)
