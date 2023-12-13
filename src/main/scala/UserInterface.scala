import ActorLogger.ActorLogger
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

object UserInterface {
  sealed trait UserCommand
  case class Critical(ts: Int) extends UserCommand
  case class NonCritical(ts: Int) extends UserCommand

  def spawnUser(id: Long, helperRef: ActorRef[HelperInterface.HelperCommand]): ActorSystem[UserCommand] =
    ActorSystem(Implementation(id, helperRef), "user")

  private object Implementation {
    private def behavior(state: ClockState)(implicit log: ActorLogger, helper: ActorRef[HelperInterface.HelperCommand]): Behaviors.Receive[UserCommand] = {
      log.log(state)
      Behaviors.receiveMessage[UserCommand] {
        case Critical(ts) => behavior {
          log.log("Request V")
          state.send(HelperInterface.ReqV(ts))
          state.tick(ts)
        }
        case NonCritical(ts) => behavior {
          log.log("Request P")
          state.send(HelperInterface.ReqP(ts))
          state.tick(ts)
        }
      }
    }

    def apply(id: Long, helper: ActorRef[HelperInterface.HelperCommand]): Behavior[UserCommand] = {
      Behaviors.setup[UserCommand] {
        context =>
          implicit val log: ActorLogger = ActorLogger(context, context.self.path.name)
          implicit val helperRef: ActorRef[HelperInterface.HelperCommand] = helper

          behavior(ClockState(0))
      }
    }
  }
}