import ActorLogger.ActorLogger
import UserInterface.{Critical, NonCritical}
import akka.actor.typed.scaladsl._
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object HelperInterface {
  sealed trait SemaphoreCommand
  case class VOP(ts: Int) extends SemaphoreCommand
  case class POP(ts: Int) extends SemaphoreCommand
  case class ACK(ts: Int) extends SemaphoreCommand

  sealed trait HelperCommand

  sealed trait NetworkCommand extends HelperCommand
  case class Connect(firstTick: Int, network: List[ActorRef[NetworkCommand]]) extends NetworkCommand
  case class Broadcast(message: SemaphoreCommand) extends NetworkCommand

  sealed trait UserReq extends HelperCommand
  case class ReqV(ts: Int) extends HelperCommand
  case class ReqP(ts: Int) extends HelperCommand

  def spawnHelper(id: Long): Behavior[HelperCommand] = Implementation(id)

  private object Implementation {
    case class SharedState(mq: List[SemaphoreCommand], network: List[ActorRef[NetworkCommand]])

    private def broadcast(state: ClockState)(message: SemaphoreCommand)
     (implicit log: ActorLogger, shared: SharedState): Behavior[HelperCommand] = {
      message match {
        case VOP(ts) => behavior {
          log.log("Broadcast VOP")
          state.tick(ts)
        }
        case POP(ts) => behavior {
          log.log("Broadcast POP")
          state.tick(ts)
        }
        case ACK(ts) => behavior {
          log.log("Broadcast ACK")
          state.tick(ts)
        }
      }
    }

    private def behavior(state: ClockState)(implicit log: ActorLogger, shared: SharedState): Behavior[HelperCommand] = {
      log.log(state)
      Behaviors.receiveMessage[HelperCommand] {
        case Connect(firstTick: Int, network) => behavior {
          log.log("Connect")
          SharedState(List(), network)
          state.tick(firstTick)
        }
        case Broadcast(message) => broadcast(state)(message)
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
          val user = UserInterface.spawnUser(id, context.self)
          val logger = ActorLogger(context, "helper")
          user ! NonCritical(0)

          behavior(ClockState(0))(logger, SharedState(List(), List()))
      }
    }
  }
}
