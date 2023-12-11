import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

object Main {
  import Semaphore._
  def main(args: Array[String]): Unit = {
    Helper(5, 1)
    }
}