package strand.lib

import common.Cancellable

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

//-----------------------------------------------------------------------------------------
class Strand private[lib] (parent: Option[Strand]):
  private val threadFactory: ThreadFactory = Thread.ofVirtual().factory()
  private val executorService =
    Executors
      .newSingleThreadScheduledExecutor(threadFactory)
  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)

  given ExecutionContext = executionContext

  private var childStrands: Set[Strand] = Set.empty

  def schedule(delay: FiniteDuration)(action: => Unit): Cancellable =
    val future = executorService.schedule[Unit](() => action, delay.length, delay.unit)
    () => future.cancel(false)

  def spawn[R](strandFactory: Strand ?=> R): R =
    val strand = Strand(Some(this))
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
  private val globalExecutor             = Executors.newVirtualThreadPerTaskExecutor()
  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(globalExecutor)

  private val root: Strand = Strand(None)

  def spawn[R](strandFactory: Strand ?=> R): Future[R] =
    Future(root.spawn(strandFactory))(using
      root.executionContext
    )

  def stop(): Unit =
    root.stop()
    globalExecutor.shutdown()
