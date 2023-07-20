package strand.lib

import common.Cancellable

import java.util.concurrent.Executors
import scala.async.Async
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

//-----------------------------------------------------------------------------------------
class Strand private[lib]():
  inline def async[T](inline x: T): Future[T]     = Async.async(x)
  extension [T](x: Future[T]) inline def await: T = Async.await(x)

  private var children: List[Strand] = Nil

  private val executorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
  given ExecutionContext      = ExecutionContext.fromExecutorService(executorService)

  def spawn[R](strandFactory: Strand ?=> R): R =
    val ctx = Strand()
    Future:
      children ::= ctx
    strandFactory(using ctx)

  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
    val future = executorService.schedule[Unit](() => action, delay.length, delay.unit)
    () => future.cancel(false)

  def stop(): Future[Unit] =
    Future
      .traverse(children)(_.stop())
      .map(_ => executorService.shutdown())

//===========================================================================================
class StrandSystem:
  private val globalExecutor = Executors.newVirtualThreadPerTaskExecutor()
  given ExecutionContext     = ExecutionContext.fromExecutorService(globalExecutor)

  private val context: Strand = Strand()
  export context.{spawn, schedule}

  def stop(): Future[Unit]           = context.stop().map(_ => globalExecutor.shutdown())
  def future[T](op: => T): Future[T] = Future(op)
