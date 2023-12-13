import HelperInterface.{HelperCommand, NetworkCommand, spawnHelper}
import UserInterface.spawnUser
import akka.actor.typed.ActorSystem


object Main {
  def main(args: Array[String]): Unit = {
    val helper: ActorSystem[HelperCommand] = ActorSystem(spawnHelper(0), "helper")
    helper ! HelperInterface.Connect(0, List(helper.ref))
  }
}