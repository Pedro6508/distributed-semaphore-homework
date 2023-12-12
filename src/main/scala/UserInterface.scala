import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object UserInterface {
  case class State(lc: Int) {
    def tick: State = State(lc + 1)
    def tick(ts: Int): State = State(math.max(lc, ts) + 1)
    def send[T](data: T)(implicit replyTo: ActorRef[T]): State = {
      replyTo ! data
      tick
    }
  }
  sealed trait Section
  case class Critical(ts: Int) extends Section
  case class NonCritical(ts: Int) extends Section
  case class DoSomething[T](ts: Int, work: State => T, replyTo: ActorRef[T]) extends Section

  def passSomeWork[T](ts: Int, replyTo: ActorRef[T])(work: State => T): DoSomething[T] =
    DoSomething[T](ts, work, replyTo)
  def spawnUser(id: Long): Behavior[Section] = Implementation(id)

  private object Implementation {
    private def behavior(state: State)(implicit defaultProc: State => Unit): Behaviors.Receive[Section] = {
      defaultProc(state)
      Behaviors.receiveMessage[Section] {
        case Critical(ts) => behavior {
          state.tick(ts)
        }
        case DoSomething(ts, work, replyTo) => behavior {
          state.send {
            work(state)
          }(replyTo)
          state.tick(ts)
        }
      }
    }

    def apply(id: Long): Behavior[Section] = {
      Behaviors.setup[Section] {
        context =>
          def log(state: State): Unit = {
            context.log.info(s"[$id]Node ${context.self.path.name} state: $state")
            Thread.sleep(1000)
          }

          behavior(State(0))(log)
      }
    }
  }
}