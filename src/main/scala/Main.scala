import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import UserInterface._


object Main {
  def main(args: Array[String]): Unit = {
    val user = ActorSystem(spawnUser(1), "user")
    val system = Behaviors.setup[String] {
      context =>
        def log(str: String): Unit = {
          context.log.info(s"[${context.self.path.name}] Received: $str")
          user ! passSomeWork(1, context.self) {
            state => s"Received the user state: $state"
          }
        }
        Behaviors.receiveMessage[String] {
          message => {
            log(message)
            Behaviors.same
          }
        }
    }
    val systemActor = ActorSystem(system, "system")
    systemActor ! "Hello World!"
  }
}