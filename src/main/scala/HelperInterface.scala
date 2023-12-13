import ActorLogger.ActorLogger
import UserInterface.{Critical, NonCritical}
import akka.actor.typed.scaladsl._
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.io.Udp.Message

object HelperInterface {
  case class SemOp(kind: SemaphoreCommand, id: Long, ts: Int) {
    def updateMap(map: Map[Long, SemOp]): Map[Long, SemOp] = map + (id -> this)
  }

  sealed trait SemaphoreCommand
  case class VOP() extends SemaphoreCommand
  case class POP() extends SemaphoreCommand

  sealed trait HelperCommand

  sealed trait NetworkCommand extends HelperCommand
  case class Connect(firstTick: Int, network: List[ActorRef[NetworkCommand]]) extends NetworkCommand
  case class Broadcast(op: SemOp) extends NetworkCommand
  case class ACK(ts: Int, Command: SemaphoreCommand) extends NetworkCommand

  sealed trait UserReq extends HelperCommand
  case class ReqV() extends HelperCommand
  case class ReqP() extends HelperCommand

  def spawnHelper(id: Long): Behavior[HelperCommand] = Implementation(id)

  private object Implementation {
    case class Shared(s: Int, flyAck: List[SemOp], network: List[ActorRef[NetworkCommand]])
    case class State(clock: ClockState, shared: Shared, unordered: Map[Long, SemOp]) { // TODO: PATCH AND COMMIT
      def mapClock(f: ClockState => ClockState) = copy(clock = f(clock))
      def mapShared(f: Shared => Shared) = copy(shared = f(shared))
      def mapUnordered(f: Map[Long, SemOp] => Map[Long, SemOp]) = copy(unordered = f(unordered))
    }

    // TODO: Define the different types of broadcast
    // Broadcast is the normal behavior
    // receive reqV -> broadcast VOP
    // receive reqP -> broadcast POP
    // receive POP v VOP -> broadcast ACK
    def broadcast(state: State)(op: SemOp) = {
      (state.unordered.get(op.id) match {
        case Some(_) =>
          state.mapUnordered(_.removed(op.id))
            .mapShared { shared => shared.copy(flyAck = op :: shared.flyAck) }
          state.mapClock(_.send(ACK(state.c))) // TODO: ADD ACK SEND TO LOGICAL CLOCK
        case None =>
          state.mapUnordered(op.updateMap)
      }).mapClock(_.tick)
    }

    // TODO: Broadcast every step, except the "{ send GO }"
    private def behavior(state: State)(implicit log: ActorLogger): Behavior[HelperCommand] = {
      log.log(state.clock)
      Behaviors.receiveMessage[HelperCommand] {
        case Connect(firstTick: Int, network) => behavior{
          log.log("Connect")
          state.copy(
            shared = state.shared.copy(network = network),
            clock = state.clock.tick(firstTick)
          )
        }
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
          val shared = SharedState(List(), List())
          user ! NonCritical(0)
          user ! Critical(0)

          behavior(shared)(ClockState(0))(logger)
      }
    }
  }
}
