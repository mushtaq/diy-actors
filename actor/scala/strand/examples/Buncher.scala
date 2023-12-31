package strand.examples

import common.Cancellable
import strand.lib.{Strand, StrandSystem}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.Future

//===========================================================================================
class Target(using strand: Strand):
  import strand.given

  def batch(messages: Vector[String]): Unit = Future:
    println(s"Got batch of ${messages.size} messages: ${messages.mkString(", ")} ")

//===========================================================================================
class Buncher(target: Target, timeout: FiniteDuration, batchSize: Int)(using strand: Strand):
  private var isIdle: Boolean        = true
  private var buffer: Vector[String] = Vector.empty
  private var timer: Cancellable     = () => true

  import strand.given

  def add(message: String): Unit = Future:
    buffer :+= message
    if isIdle then whenIdle() else whenActive()

  private def whenIdle(): Unit =
    timer = strand.schedule(timeout):
      deliverBatch()
    isIdle = false

  private def whenActive(): Unit =
    if buffer.size == batchSize then
      deliverBatch()
      timer.cancel()

  private def deliverBatch(): Unit =
    target.batch(buffer)
    buffer = Vector.empty
    isIdle = true

//===========================================================================================
class BuncherTest(using strand: Strand):
  private val target: Target   = strand.spawn(Target())
  private val buncher: Buncher = strand.spawn(Buncher(target, 3.seconds, 10))

  (1 to 15).foreach: x =>
    buncher.add(x.toString)

  strand.schedule(1.seconds):
    buncher.add("16")

  strand.schedule(2.seconds):
    buncher.add("17")

  strand.schedule(4.seconds):
    buncher.add("18")

//===========================================================================================
@main
def buncherApp(): Unit =
  val system = StrandSystem()
  system.spawn(BuncherTest())
//  io.StdIn.readLine()
//  system.stop()
