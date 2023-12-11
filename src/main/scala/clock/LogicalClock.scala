package clock

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl._

object EntryPoint {
  val system: ActorSystem[LogicalClock.Command] = ActorSystem(LogicalClock(), "clock")
  system ! LogicalClock.Tick(system)
}

object LogicalClock {
  sealed trait Command
  final case class Tick(replyTo: ActorRef[Command]) extends Command
  final case class Tock(replyTo: ActorRef[Command]) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case Tick(replyTo) =>
          context.log.info("Ticked")
          replyTo ! Tock(context.self)
          Behaviors.same
        case Tock(replyTo) =>
          context.log.info("Tocked")
          replyTo ! Tick(context.self)
          Behaviors.same
      }
    }
  }
}
