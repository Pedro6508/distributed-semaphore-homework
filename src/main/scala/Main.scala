import akka.actor.typed.ActorSystem
import example.TickTack
import zio._
import zio.Console.printLine

object Main {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem(TickTack(), "clock")
    system ! TickTack.Tick(system)
  }
}