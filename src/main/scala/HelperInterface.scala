import ActorLogger.ActorLogger
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object HelperInterface {
  sealed trait SemaphoreCommand
  case class VOP(ts: Int) extends SemaphoreCommand
  case class POP(ts: Int) extends SemaphoreCommand
  case class ACK(ts: Int) extends SemaphoreCommand

  sealed trait HelperCommand

  sealed trait NetworkCommand extends HelperCommand
  case class Connect(firstTick: Int, network: ActorRef[NetworkCommand]) extends NetworkCommand
  case class Broadcast(message: SemaphoreCommand) extends NetworkCommand

  sealed trait UserReq extends HelperCommand
  case class ReqV(ts: Int) extends HelperCommand
  case class ReqP(ts: Int) extends HelperCommand

  private object Implementation {
    def behavior(state: ClockState)(implicit log: ActorLogger): Behavior[HelperCommand] = {
      log.log(state)
      Behaviors.receiveMessage[HelperCommand] {
        case ReqV(ts) => behavior {
          log.log("Broadcast VOP")
          state.tick(ts)
        }
        case ReqP(ts) => behavior {
          log.log("Broadcast VOP")
          state.tick(ts)
        }
      }
    }

    def apply(id: Long): Behavior[HelperCommand] = {
      Behaviors.setup[HelperCommand] {
        context =>
          val user = context.spawn(UserInterface.spawnUser(id), s"user$id")
          val logger = ActorLogger(context)

          behavior(ClockState(0))(logger)
      }
    }
  }
}
