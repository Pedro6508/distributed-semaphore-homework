import ActorLogger.ActorLogger
import UserInterface.{Critical, NonCritical}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.immutable.{AbstractMap, SeqMap, SortedMap, SortedSet}
import scala.language.implicitConversions

object HelperInterface {
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
  final case class P(ts: Int, sender: Long) extends Operation
  final case class V(ts: Int, sender: Long) extends Operation

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
  case class VOV(ts: Int, sender: Long) extends HelperMsg
  case class ReqV(ts: Int, sender: Long) extends HelperMsg
  case class ReqP(ts: Int, sender: Long) extends HelperMsg

  def spawnHelper(id: Long, s: Int = 0): ActorRef[HelperMsg] = ActorSystem(Implementation(id, s), s"helper-$id")

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

      def push(op: Operation): SharedState = copy(todo = todo + op)

      def updateSem(op: Operation): SharedState = op match {
        case P(_, _) => copy(s = s + 1)
        case V(_, _) => copy(s = s - 1)
      }
    }
    case class State(clock: ClockState, shared: SharedState) {
      def apply(f: State => State): State = f(this)
      def flatMap[A](f: State => A)(g: A => State): State = g(f(this))
    }

    case class Helper(id: Long, self: ActorRef[HelperMsg], network: List[ActorRef[HelperMsg]]) {
      def log(state: State): ClockState = state.clock

      private def broadcast(state: State)(op: Operation): State = state.copy {
        network.foldLeft(state.clock)(
          (clock, ref) => op.apply(clock)(ref)
        )
      }

      def receive(state: State)(msg: HelperMsg)(implicit f: HelperMsg => Operation): State =
        state.apply { st =>
          if (st.shared.isACK(msg)) knowMsg(state)(msg)
          else newMsg(state)(msg)
        }

      private def knowMsg(state: State)(msg: HelperMsg)(implicit f: HelperMsg => Operation): State =
        state.copy(
          clock = state.clock.tick(msg.time),
          shared = state.shared.push(msg)
        )

      private def newMsg(state: State)(msg: HelperMsg)(implicit f: HelperMsg => Operation): State = {
        broadcast(state)(msg).copy(
          clock = state.clock.tick(msg.time),
          shared = state.shared.markOp(msg)
        )
      }
    }

    private def behavior(helper: Helper)(state: State)
      (implicit log: ActorLogger, convert: HelperMsg => Operation): Behavior[HelperMsg] = {
      log.log(state.clock)

      Behaviors.receiveMessage[HelperMsg] {
        case Connect(firstTick: Int, network) =>
          behavior(helper.copy(network = network)) {
            log.log(s"Connect | s0: $firstTick | Network: [${network.map(_.path.name).mkString(", ")}]")
            state.copy(
              clock = state.clock.tick(firstTick),
              shared = state.shared.copy(s = firstTick)
            )
          }
        case msg => helper.receive(state)(msg)(convert) match {
          case newState @ State(_, SharedState(s, _, _)) =>
            log.log(s"Shared State: s = $s")
            behavior(helper)(newState)
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
          val helper = Helper(id, context.self, List(context.self))
          val state = State(
            ClockState.apply(0),
            SharedState(s = s0, todo = SortedSet[Operation](), Map[Operation, Boolean]())
          )

          val logger = ActorLogger(context, s"helper-$id")
          user ! NonCritical(0, id)
          user ! Critical(0, id)

          behavior(helper)(state)(logger, convert)
      }
    }
  }
}
