package ircbot

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import InnerProtocol._
import utils._
import scala.concurrent.duration._
import scala.concurrent._
import ExecutionContext.Implicits.global
import java.util.concurrent.TimeoutException
import db._

abstract class Module extends Actor with RemoteLogger with HelpInfo {
  val ctl: ActorRef

  implicit val tm = Timeout(10.seconds)

  def send(message: Message) {
    ctl ! SendMessage(message)
  }

  protected def getUser(nick: Nick): Future[Option[User]] = {
    ctl.ask(SendTo("auth", AuthGetUser(nick)))(10.seconds).mapTo[Option[User]].fallbackTo(Future(None))
  }

  def requireGranted(nick: Nick, lvl: UserLevel)(onGranted: => Any) = {
    ifGranted(nick, lvl) {
      onGranted
    } {
      send(Msg(nick, "Permission denied: this command requires at least the "+lvl+" right!"))
    }
  }

  def ifGranted[T](nick: Nick, lvl: UserLevel)(onGranted: => T)(onNotGranted: => T): Future[T] = {
    getUser(nick).map { 
      case Some(u) =>
        if (u.userLevel >= lvl) {
          onGranted
        } else {
          onNotGranted
        }
      case None =>
        onNotGranted
    }
  }

  def currentState: Future[BotState] = {
    ctl.ask(SendTo("protocol", RequestBotState))(2.seconds).mapTo[BotState]
  }

  def requireOP(chan: Channel)(whenOp: => Any) = {
    ctl.ask(SendTo("op", RequestOp(chan)))(10.seconds).foreach { r =>
      whenOp
    }
  }

  def words(str: String): List[String] = words(str, 0)

  def words(str: String, limit: Int): List[String] =
    str.split("[:,. ]", limit).toList

  def receive = {
    case Connected =>
      onConnect()

    case Disconnected =>
      onDisconnect()

    case Init =>
      onInit()

    case _ =>
  }

  def onInit(): Unit = {
    ctl ! SendTo("help", HelpEntries(helpEntries))
  }

  def onDisconnect(): Unit = {}
  def onConnect(): Unit = {}

}

abstract class SimpleModule extends Module with HelpInfo {
  override def receive = {
    case ReceivedMessage(msg) =>
      onMessage(msg)

    case m => super.receive(m)
  }

  def onMessage(message: Message): Unit
}
