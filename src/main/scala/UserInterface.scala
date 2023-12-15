import ActorLogger.ActorLogger
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

object UserInterface {
  sealed trait UserCommand
  case class Critical(ts: Int, sender: Long) extends UserCommand
  case class NonCritical(ts: Int, sender: Long) extends UserCommand

  def spawnUser(id: Long, helperRef: ActorRef[HelperInterface.HelperMsg]): ActorSystem[UserCommand] =
    ActorSystem(UserInterface.Implementation(helperRef), s"user-$id")

  private object Implementation {
    private def behavior(state: ClockState)(implicit log: ActorLogger, helper: ActorRef[HelperInterface.HelperMsg]): Behaviors.Receive[UserCommand] = {
      Behaviors.receiveMessage[UserCommand] {
        case Critical(ts, id) => behavior {
          log.log("Request V")
          state.send(HelperInterface.ReqV(ts, id))
          state.tick(ts)
        }
        case NonCritical(ts, id) => behavior {
          log.log("Request P")
          state.send(HelperInterface.ReqP(ts, id))
          state.tick(ts)
        }
      }
    }

    def apply(helper: ActorRef[HelperInterface.HelperMsg]): Behavior[UserCommand] = {
      Behaviors.setup[UserCommand] {
        context =>
          implicit val log: ActorLogger = ActorLogger(context, context.self.path.name)
          implicit val helperRef: ActorRef[HelperInterface.HelperMsg] = helper

          behavior(ClockState(0))
      }
    }
  }
}