import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.ActorRef

case class ClockState(lc: Int) {
  def tick: ClockState = copy(lc = lc + 1)
  def tick(ts: Int): ClockState = copy(lc = math.max(lc, ts) + 1)
  def send[T](data: T)(implicit replyTo: ActorRef[T]): ClockState = {
    replyTo ! data
    tick
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