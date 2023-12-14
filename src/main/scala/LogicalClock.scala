import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.ActorRef

import scala.collection.immutable.SortedSet
import scala.language.implicitConversions

case class ClockState(lc: Int) {
  def tick: ClockState = copy(lc = lc + 1)
  def tick(ts: Int): ClockState = copy(lc = math.max(lc, ts) + 1)
  def send[T](data: T)(implicit replyTo: ActorRef[T]): ClockState = {
    replyTo ! data
    tick
  }
}
sealed trait Operation extends Ordered[Operation] {
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

object DistributedSemaphore {
  case class SharedState(s: Int, todo: SortedSet[Operation])
  case class State(clock: ClockState, shared: SharedState) {
    def broadcast[T](data: T)(implicit network: List[ActorRef[T]]): ClockState = {
      network.foreach(_ ! data)
      clock.tick
    }

    private def ack(mapACK: Map[Long, Boolean])
      (implicit maybeSend: (Operation, ClockState) => ClockState): State = {
        val (remainder, execute) = shared.todo.span(op => mapACK.isDefinedAt(op.from))
        execute.foldLeft(
          copy(shared = shared.copy(todo = remainder))
        ){
          case (State(clock, shared), op: P) if (shared.s > 0) =>
            copy(maybeSend(op, clock), shared.copy(s = shared.s - 1))
          case (State(clock, shared), _) => copy(
            shared = shared.copy(s = shared.s + 1)
          )
        }

  }
}

object ActorLogger {
  trait ActorLogger {
    def log(state: ClockState): Unit
    def log(str: String): Unit
  }

  def apply[T](context: ActorContext[T], name: String): ActorLogger = new ActorLogger {
    override def log(state: ClockState): Unit = {
      context.log.info(s"[$name] clock: $state")
      Thread.sleep(1000)
    }

    override def log(str: String): Unit = {
      context.log.info(s"[$name] $str")
      Thread.sleep(1000)
    }
  }
}