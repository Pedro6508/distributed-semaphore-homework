import ActorLogger.ActorLogger
import UserInterface.{Critical, NonCritical}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.immutable.{AbstractMap, SeqMap, SortedMap, SortedSet}
import scala.language.implicitConversions

trait Operation extends Ordered[Operation] {
  override def compare(that: Operation): Int = this.time - that.time

  def time: Int = this match {
    case P(ts, _) => ts
    case V(ts, _) => ts
  }

  def from: Long = this match {
    case P(_, from) => from
    case V(_, from) => from
  }
}
final case class P(ts: Int, sender: Long) extends Operation
final case class V(ts: Int, sender: Long) extends Operation

object HelperInterface {
  sealed trait HelperCommand

  sealed trait NetworkCommand extends HelperCommand
  case class Connect(firstTick: Int, network: List[ActorRef[HelperCommand]]) extends NetworkCommand
  case class Broadcast(op: Operation) extends NetworkCommand

  sealed trait UserReq extends HelperCommand {
    def time: Int = this match {
      case ReqV(ts) => ts
      case ReqP(ts) => ts
    }
  }
  case class ReqV(ts: Int) extends UserReq
  case class ReqP(ts: Int) extends UserReq

  def spawnHelper(id: Long): Behavior[HelperCommand] = Implementation(id)

  private object Implementation {
    case class SharedState(s: Int, todo: SortedSet[Operation], knowMap: Map[Operation, Boolean]) {
      def markOp(op: Operation): SharedState = {
        val newKnowMap: Map[Operation, Boolean] = knowMap.get(op) match {
          case Some(isACK) =>
            if (isACK) knowMap + (op -> isACK)
            else knowMap + (op -> true)
          case None => knowMap + (op -> false)
        }

        copy(knowMap = newKnowMap)
      }

      def isACK(op: Operation): Boolean = knowMap.getOrElse(op, false)

      def updateSem(op: Operation): SharedState = op match {
        case op: P => copy(s = s - 1)
        case op: V => copy(s = s + 1)
      }
    }
    case class State(clock: ClockState, shared: SharedState) {
      def apply(f: State => State): State = f(this)

      private def ack(knowMsgs: Map[Long, Boolean])
                     (implicit maybeSend: (Operation, ClockState) => ClockState): State = {
        val (remainder, execute) = shared.todo.span(op => knowMsgs.isDefinedAt(op.from))
        execute.foldLeft(
          copy(shared = shared.copy(todo = remainder))
        ) {
          case (State(cl, sh), op: P) if (sh.s > 0) =>
            copy(maybeSend(op, cl), sh.copy(s = sh.s - 1))
          case (State(_, sh), _) =>
            copy(shared = sh.copy(s = sh.s + 1)
            )
        }
      }
    }

    case class Helper(id: Long, self: ActorRef[HelperCommand], network: List[ActorRef[HelperCommand]]) {
      def log(state: State) = state.clock

      private def broadcast(cl: ClockState)(op: Operation): ClockState = cl.tick {
        network.foreach(_ ! Broadcast(op))
        op.time + 1
      }

      def receiveBroadcast(state: State)(bd: Broadcast): State = state.apply {
        case State(clock: ClockState, shared: SharedState) if !shared.isACK(bd.op) =>
          State(
            clock = broadcast(clock)(bd.op),
            shared = shared.markOp(bd.op)
          )
        case state: State => state.copy(clock = state.clock.tick(bd.op.time))
      }

      def receivedRequest(state: State)(uq: UserReq): State = state.copy {
        broadcast(state.clock)(
          uq match {
            case ReqV(ts) => P(ts, id)
            case ReqP(ts) => V(ts, id)
          }
        )
      }
    }

    private def behavior(helper: Helper)(state: State)(implicit log: ActorLogger): Behavior[HelperCommand] = {
      log.log(state.clock)
      Behaviors.receiveMessage[HelperCommand] {
        case Connect(firstTick: Int, network) =>
          behavior(helper.copy(network = network)) {
            log.log("Connect")
            state.copy {
              state.clock.tick(firstTick)
            }
          }
        case broadcast: Broadcast => behavior(helper) {
          log.log(s"Received Broadcast $broadcast")
          helper.receiveBroadcast(state)(broadcast)
        }
        case req: UserReq => behavior(helper) {
          log.log(s"Received Broadcast $req")
          helper.receivedRequest(state)(req)
        }
      }
    }

    def apply(id: Long, s0: Int = 0): Behavior[HelperCommand] = {
      Behaviors.setup[HelperCommand] {
        context =>
          val user = UserInterface.spawnUser(id, context.self)
          val helper = Helper(id, context.self, List(context.self))
          val state = State(
            ClockState.apply(0),
            SharedState(s = s0, todo = SortedSet[Operation](), Map[Operation, Boolean]())
          )

          val logger = ActorLogger(context, "helper")
          user ! NonCritical(0)
          user ! Critical(0)

          behavior(helper)(state)(logger)
      }
    }
  }
}
