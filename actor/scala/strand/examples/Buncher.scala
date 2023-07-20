package strand.examples

import common.Cancellable
import strand.lib.{Context, StrandSystem}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

//===========================================================================================
class Target(using context: Context):
  import context.async
  def batch(messages: Vector[String]): Future[Unit] = async:
    println(s"Got batch of ${messages.size} messages: ${messages.mkString(", ")} ")

//===========================================================================================
class Buncher(target: Target, after: FiniteDuration, maxSize: Int)(using context: Context):
  import context.async
  private var isIdle: Boolean        = true
  private var buffer: Vector[String] = Vector.empty
  private var timer: Cancellable     = () => true

  def info(message: String): Future[Unit] = async:
    buffer :+= message
    if isIdle then onIdle() else onActive()

  private def onIdle(): Unit =
    timer = context.schedule(after):
      sendBatchAndIdle()
    isIdle = false

  private def onActive(): Unit =
    if buffer.size == maxSize then
      sendBatchAndIdle()
      timer.cancel()

  private def sendBatchAndIdle(): Unit =
    target.batch(buffer)
    buffer = Vector.empty
    isIdle = true

//===========================================================================================
class BuncherTest(using context: Context):
  private val target: Target   = context.spawn(Target())
  private val buncher: Buncher = context.spawn(Buncher(target, 3.seconds, 10))

  (1 to 15).foreach: x =>
    buncher.info(x.toString)

  context.schedule(1.seconds):
    buncher.info("16")

  context.schedule(2.seconds):
    buncher.info("17")

  context.schedule(4.seconds):
    buncher.info("18")

//===========================================================================================
@main
def buncherApp: Unit =
  val system = StrandSystem()
  system.spawn(BuncherTest())
//    StdIn.readLine()
//    system.stop()
