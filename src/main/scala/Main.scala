import HelperInterface.{ NetworkCommand, spawnHelper}
import UserInterface.spawnUser
import akka.actor.typed.ActorSystem


object Main {
  case class Module(name: String, keyword: String , date: Int)

  def main(args: Array[String]): Unit = {
    val s = 2
    val network = (0 to 3).map(i => spawnHelper(i.toLong)).toList

    network.foreach(_ ! HelperInterface.Connect(s, network))
    network.foreach(_ ! HelperInterface.POV(s, 0))
  }
}