
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.collection.mutable

sealed trait Op
case class P(id: Long) extends Op
case class V() extends Op

sealed trait NetworkOps
case class Request[T](op: Op, ts: Int) extends NetworkOps  // Request Operation P or V
case class Execute[T](op: Op, ts: Int) extends NetworkOps  // Process Operation P or V
case class ACK(lc: Int, id: Long) extends NetworkOps       // Acknowledge Message
case class Broadcast(netOp: NetworkOps) extends NetworkOps

sealed trait User
case class Go(ts: Int) extends User   // User Go

object User {
  def apply(helper: ActorRef[NetworkOps], id: Long): Behavior[User] = {
    Behaviors.setup { context =>
      def clock(lc: Int): Behavior[User] = {
        Behaviors.receiveMessage {
          case Go(ts) =>
            val lc1 = math.max(lc, ts) + 1

            helper ! Request(P(id), lc1)
            context.log.info(s"Node ${context.self.path.name} sent request P")
            val lc2 = lc1 + 1

            helper ! Request(V(), lc2)
            context.log.info(s"Node ${context.self.path.name} sent request V")
            val lc3 = lc2 + 1

            clock(lc3)
        }
      }
      clock(0)
    }
  }
}

object Semaphore {

  case class NodeOp(id: Long, from: ActorRef[NetworkOps])

  def Network(nodes: List[NodeOp]): Behavior[Broadcast] = {
    nodes.foreach(node => node.from ! Broadcast(Request(V(), 0)))
    Behaviors.receiveMessage {
      case Broadcast(op) => op match {
        case Execute(op, ts) =>
          nodes.foreach(node => node.from ! Execute(op, ts))
          Behaviors.same
        case ACK(ts, id) =>
          nodes.foreach(node =>
            node.from ! ACK(ts, id))
          Behaviors.same
      }
    }
  }

  private case class TimeOp(op: Op, ts: Int)
  private case class State(todo: List[TimeOp], s: Int) {
    implicit val ord: Ordering[TimeOp] = Ordering.by(_.ts)

    def push(op: Op, ts: Int) = copy(todo = TimeOp(op, ts) :: todo)

    def sort(f: (TimeOp, State) => State) = todo.sorted(ord).foldRight(State(List(), s)) {
      case (top, state) => f(top, state)
    }
  }

  def Helper(n: Int, id: Long): Behavior[NetworkOps] = {
    Behaviors.setup { context =>
      val nodes = (1 to n).map(
        id => NodeOp(id, context.self)
      ).toList
      val network = context.spawn(Network(nodes), "network")
      val user = context.spawn(User(context.self, id), s"user$id")

      def helper(lc: Int)(implicit state: State): Behavior[NetworkOps] = {
        Behaviors.receiveMessage {
          case Request(op, ts) =>
            val lc1 = math.max(lc, ts) + 1

            network ! Broadcast(Execute(op, lc1))
            context.log.info(s"Node ${context.self.path.name} sent execute $op")
            val lc2 = lc1 + 1

            helper(lc2)
          case Execute(op, ts) =>
            val lc1 = math.max(lc, ts) + 1

            network ! Broadcast(ACK(lc1, id))
            context.log.info(s"Node ${context.self.path.name} sent ACK")
            val lc2 = lc1 + 1

            helper(lc2)(state.copy(
              todo = TimeOp(op, lc2) :: state.todo)
            )
          case ACK(lc, id) =>
            val ls1 = state.sort((top, state) => top.op match {
              case V() =>
                state.copy(s = state.s + 1)
              case _ => state.copy(top :: state.todo)
            })

            val ls2 = ls1.sort((top, state) => top.op match {
              case P(opId) =>
                if (opId == id) {
                  user ! Go(lc)
                  context.log.info(s"Node ${context.self.path.name} sent Go")
                  state.copy(s = state.s - 1)
                } else state.copy(state.todo)
              case _ => state.copy(top :: state.todo)
            })

            helper(lc)(ls2)
        }
      }
      helper(0)(State(List(), 0))
    }
  }
}