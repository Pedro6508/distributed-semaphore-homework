import akka.actor.typed.ActorSystem
import clock.LogicalClock
import zio._
import zio.Console.printLine

object Main {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem(LogicalClock(), "clock")
    system ! LogicalClock.Tick(system)
  }
}