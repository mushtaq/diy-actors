package strand.lib

import common.Cancellable

import java.util.concurrent.Executors
import scala.async.Async
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

//-----------------------------------------------------------------------------------------
class Strand private[lib] (parent: Option[Strand]):
  parentStrand =>

  inline def async[T](inline x: T): Future[T]     = Async.async(x)
  extension [T](x: Future[T]) inline def await: T = Async.await(x)

  private var childStrands: Set[Strand] = Set.empty

  private val executorService            = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

  given ExecutionContext = executionContext

  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
    val future = executorService.schedule[Unit](() => action, delay.length, delay.unit)
    () => future.cancel(false)

  def spawn[R](strandFactory: Strand ?=> R): R =
    val strand = Strand(Some(parentStrand))
    childStrands += strand
    strandFactory(using strand)

  def stop(): Unit = Future:
    childStrands.foreach(_.stop())
    executorService.shutdown()
    parent.foreach(_.remove(this))

  def remove(child: Strand): Unit = Future:
    childStrands -= child

//===========================================================================================
class StrandSystem:
  private val globalExecutor = Executors.newVirtualThreadPerTaskExecutor()
  given ExecutionContext     = ExecutionContext.fromExecutorService(globalExecutor)

  private val rootStrand: Strand = Strand(None)

  def spawn[R](strandFactory: Strand ?=> R): Future[R] =
    Future(rootStrand.spawn(strandFactory))(rootStrand.executionContext)

  def stop(): Unit =
    rootStrand.stop()
    globalExecutor.shutdown()
