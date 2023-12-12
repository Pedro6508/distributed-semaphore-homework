package example

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl._

object EntryPoint {
  val system: ActorSystem[TickTack.Command] = ActorSystem(TickTack(), "clock")
  system ! TickTack.Tick(system)
}

object TickTack {
  sealed trait Command
  final case class Tick(replyTo: ActorRef[Command]) extends Command
  final case class Tack(replyTo: ActorRef[Command]) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.setup {
      context =>
      def log(text: String): Unit = {
        context.log.info(text)
        Thread.sleep(1000)
      }

      Behaviors.receiveMessage {
        case Tick(replyTo) =>
          log("Ticked")

          replyTo ! Tack(context.self)
          Behaviors.same
        case Tack(replyTo) =>
          log("Tacked")

          replyTo ! Tick(context.self)
          Behaviors.same
      }
    }
  }
}
