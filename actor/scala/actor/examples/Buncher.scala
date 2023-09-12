package actor.examples

import common.Cancellable
import actor.examples.Command.*
import actor.examples.Target.Batch
import actor.lib.{Actor, ActorRef, ActorSystem, Context}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

//-----------------------------------------------------------------------------------------
object Target:
  case class Batch(commands: Vector[Command])

//-----------------------------------------------------------------------------------------
class Target(using Context[Batch]) extends Actor[Batch]:
  override def receive(message: Batch): Unit =
    println(s"Got batch of ${message.commands.size} messages: ${message.commands.mkString(", ")} ")

//-----------------------------------------------------------------------------------------
enum Command:
  case Message(msg: String)
  private[examples] case Timeout

//===========================================================================================
class Buncher(
    target: ActorRef[Batch],
    after: FiniteDuration,
    maxSize: Int
)(using Context[Command])
    extends Actor[Command]:

  private var isIdle             = true
  private var buffer             = Vector.empty[Command]
  private var timer: Cancellable = () => true

  override def receive(cmd: Command): Unit =
    if isIdle
    then whenIdle(cmd)
    else whenActive(cmd)

  private def whenIdle(cmd: Command): Unit =
    buffer :+= cmd
    timer = context.schedule(after):
      context.self.send(Timeout)
    isIdle = false

  private def whenActive(cmd: Command): Unit =
    cmd match
      case Timeout =>
        deliverBatch()
      case Message(msg) =>
        buffer :+= cmd
        if buffer.size == maxSize then
          deliverBatch()
          timer.cancel()

  private def deliverBatch(): Unit =
    target.send(Batch(buffer))
    buffer = Vector.empty
    isIdle = true

//=========================================================================================
class BuncherTest(using Context[Unit]) extends Actor[Unit]:
  override def receive(message: Unit): Unit = ()

  private val target: ActorRef[Batch]    = context.spawn(Target())
  private val buncher: ActorRef[Command] = context.spawn(Buncher(target, 3.seconds, 10))

  (1 to 15).foreach: x =>
    buncher.send(Message(x.toString))

  context.schedule(1.seconds):
    buncher.send(Message("16"))

  context.schedule(2.seconds):
    buncher.send(Message("17"))

  context.schedule(4.seconds):
    buncher.send(Message("18"))

//===========================================================================================
@main
def buncherMain(): Unit =
  println("*******************")
  val system = ActorSystem()
  system.spawn(BuncherTest())
//    StdIn.readLine()
//    system.stop()
