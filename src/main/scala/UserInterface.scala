import ActorLogger.ActorLogger
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object UserInterface {
  sealed trait UserCommand
  case class Critical(ts: Int) extends UserCommand
  case class NonCritical(ts: Int) extends UserCommand
  case class DoSomething[T](ts: Int, work: ClockState => T, replyTo: ActorRef[T]) extends UserCommand

  def passSomeWork[T](ts: Int, replyTo: ActorRef[T])(work: ClockState => T): DoSomething[T] =
    DoSomething[T](ts, work, replyTo)
  def spawnUser(id: Long): Behavior[UserCommand] = Implementation(id)

  private object Implementation {
    private def behavior(state: ClockState)(implicit log: ActorLogger): Behaviors.Receive[UserCommand] = {
      log.log(state)
      Behaviors.receiveMessage[UserCommand] {
        case Critical(ts) => behavior {
          log.log("Request V")
          state.tick(ts)
        }
        case NonCritical(ts) => behavior {
          log.log("Request P")
          state.tick(ts)
        }
      }
    }

    def apply(id: Long): Behavior[UserCommand] = {
      Behaviors.setup[UserCommand] {
        context =>
          context.setLoggerName(s"[User $id]\t${context.self.path.name}: ")
          val log = ActorLogger(context)

          behavior(ClockState(0))(log)
      }
    }
  }
}