import ActorLogger.ActorLogger
import UserInterface.{Critical, NonCritical}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.annotation.unused
import scala.collection.immutable.SortedSet
import scala.language.implicitConversions

object HelperInterface {
  @unused
  sealed trait Operation extends Ordered[Operation]{
    override def compare(that: Operation): Int = this.time - that.time

    def time: Int = this match {
      case P(ts, _) => ts
      case V(ts, _) => ts
    }

    def from: Long = this match {
      case P(_, from) => from
      case V(_, from) => from
    }

    def apply(clock: ClockState)(replyTo: ActorRef[HelperMsg]): ClockState =
      this match {
        case P(ts, sender) => clock.send(POV(ts, sender))(replyTo)
        case V(ts, sender) => clock.send(VOV(ts, sender))(replyTo)
      }
  }
  private final case class P(ts: Int, sender: Long) extends Operation
  private final case class V(ts: Int, sender: Long) extends Operation

  sealed trait HelperMsg {
    def time: Int = this match {
      case POV(ts, _) => ts
      case VOV(ts, _) => ts
      case ReqV(ts, _) => ts
      case ReqP(ts, _) => ts
    }

    def from: Long = this match {
      case POV(_, from) => from
      case VOV(_, from) => from
      case ReqV(_, from) => from
      case ReqP(_, from) => from
    }
  }

  sealed trait NetworkCommand extends HelperMsg
  case class Connect(firstTick: Int, network: List[ActorRef[HelperMsg]]) extends NetworkCommand

  case class POV(ts: Int, sender: Long) extends HelperMsg
  private case class VOV(ts: Int, sender: Long) extends HelperMsg
  case class ReqV(ts: Int, sender: Long) extends HelperMsg
  case class ReqP(ts: Int, sender: Long) extends HelperMsg

  def spawnHelper(id: Long, s: Int = 0): ActorRef[HelperMsg] = ActorSystem(Implementation(id, s), s"helper-$id")

  private object Implementation {
    case class SharedState(s: Int, todo: SortedSet[Operation])
    case class State(clock: ClockState, shared: SharedState, onBroadcast: Map[Long, Int]) {
      def apply(f: State => State): State = f(this)
      def flatMap[A](f: State => A)(g: A => State): State = g(f(this))
    }

    private case class Helper(id: Long, self: ActorRef[HelperMsg], user: ActorRef[UserInterface.UserCommand], network: List[ActorRef[HelperMsg]]) {
      def broadcast(state: State)(op: Operation): State = state.copy {
        network.foldLeft(state.clock)(
          (clock, ref) => op.apply(clock)(ref)
        )
      }

      def clean(state: State): State = state.shared.todo.foldLeft(state) {
        case (st, op) if st.shared.s > 0 =>
          lazy val s =
            if (op.isInstanceOf[V]) st.shared.s + 1
            else st.shared.s - 1
          lazy val goUser =
            if (op.isInstanceOf[P])
              st.clock.send(Critical(op.time, id))(user)
            else
              st.clock.send(Critical(op.time, id))(user)

          st.copy(
            clock = if (op.from == id) goUser else st.clock,
            shared = st.shared.copy(
              todo = st.shared.todo - op,
              s = s
            )
          )
        case (st, _) => st
      }

      def onBroadcast(state: State)(msg: HelperMsg): State = {
        state.copy(
          onBroadcast = state.onBroadcast.updated(msg.from, state.onBroadcast.getOrElse(msg.from, 0) + 1),
          clock = state.clock.tick(msg.time)
        )
      }

      def newMsg(state: State)(msg: HelperMsg)(implicit f: HelperMsg => Operation): State = {
        broadcast(state)(msg).copy(
          clock = state.clock.tick(msg.time),
          onBroadcast = state.onBroadcast + (msg.from -> 1),
          shared = state.shared.copy(todo = state.shared.todo + msg)
        )
      }
    }

    private def behavior(helper: Helper)(state: State)
      (implicit log: ActorLogger, convert: HelperMsg => Operation): Behavior[HelperMsg] = {
      log.log(s"[${state.clock.lc}] Shared: ${state.shared}")
      Behaviors.receiveMessage[HelperMsg] {
        case Connect(firstTick: Int, network) =>
          behavior(helper.copy(network = network)) {
            log.log(s"Connect | s0: $firstTick | Network: [${network.map(_.path.name).mkString(", ")}]")
            helper.user ! NonCritical(firstTick, helper.id)
            state.copy(
              clock = state.clock.tick(firstTick),
              shared = state.shared.copy(s = firstTick)
            )
          }
        case msg @ (POV(_, _) | VOV(_, _)) => behavior(helper) {
          state.onBroadcast.getOrElse(msg.from, 0) match {
            case 0 =>
              log.log(s"New message: $msg")
              helper.newMsg(state)(msg)
            case counter if counter == helper.network.size =>
              log.log(s"Message from all: $msg")
              helper.clean(state)
            case counter =>
              log.log(s"Message from $counter/${helper.network.size}: $msg")
              helper.onBroadcast(state)(msg)
          }
        }
        case msg @ (ReqV(_, _) | ReqP(_, _)) => behavior(helper) {
          log.log(s"Request: $msg")
          helper.broadcast(state)(msg)
        }
        case _ => behavior(helper) {
          log.log(s"Unknown message")
          state
        }
      }
    }

    def apply(id: Long, s0: Int = 0): Behavior[HelperMsg] = {
      implicit def convert(msg: HelperMsg): Operation =
        if (msg.isInstanceOf[POV] || msg.isInstanceOf[ReqP]) P(msg.time, msg.from)
        else if (msg.isInstanceOf[VOV] || msg.isInstanceOf[ReqV]) V(msg.time, msg.from)
        else throw new Exception("Invalid message type")

      Behaviors.setup[HelperMsg] {
        context =>
          val user = UserInterface.spawnUser(id, context.self)
          val helper = Helper(id, context.self, user, List(context.self))
          val state = State(
            ClockState.apply(0),
            SharedState(s = s0, todo = SortedSet[Operation]()),
            Map[Long, Int]()
          )

          val logger = ActorLogger(context, s"helper-$id")
          behavior(helper)(state)(logger, convert)
      }
    }
  }
}
