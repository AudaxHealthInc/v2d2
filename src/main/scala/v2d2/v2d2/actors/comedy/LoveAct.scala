package v2d2.actions.love

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorContext, ActorLogging, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import v2d2.V2D2
import slack.models.Message
import scala.concurrent.ExecutionContext.Implicits.global
import v2d2.protocols.{EphemResponse, Response, SlashRelay}
import scala.collection.mutable._

class LoveAct extends Actor with ActorLogging with LoveJsonProtocol {

  implicit val timeout      = Timeout(25.seconds)
  implicit val system       = ActorSystem("slack")

  var guessing: Map[String, Queue[GuessLove]] = Map()

  def loving(
    message: Option[String]
  ): String = {
    message match {
      case Some(str) if str.trim() != "" =>
        str
      case _ =>
        "something random"
    }
  }

  def receive: Receive = {
    case guess: Guess =>
      guess.targets.map { tg =>
        guessing.get(guess.msg.user).map { q =>
          val default = Crush(q.head.message) match {
            case Some(crush) => crush.reason.getOrElse("something")
            case _           => "something"
          }
          if (q.head.message.user == tg) {
            // you got it correct relay message
            sender ! EphemResponse(
              guess.msg,
              s"""That's right! It was <@${q.head.message.user}> who sent you:
                 |\t:heart: ${default} :heart:""".stripMargin
              // + s"""${if(q.head.size > 1) s"\n\tyou have ${q.head.size - 1} :heart: to find" }"""
            )
            q.dequeue
          } else if (q.head.count < 2 && !guess.giveUp) {
            // you got it wrong relay message inc count
            q.update(0, q.head.copy(count = q.head.count + 1))
            sender ! EphemResponse(
              guess.msg,
              s"""keep trying and you'll get better :musical_note:
                 |\t:question: ${default} :question:
                 |\tyou have ${3 - q.head.count} guesses remaing and ${q.size} :hearts: to find""".stripMargin
            )
          } else {
            // you got it wrong too many times fail
            val end = if (q.size > 1) s"\n\tyou have ${q.size - 1} :heart: to find" else ""
            sender ! EphemResponse(
              guess.msg,
              s"""All along it was <@${q.head.message.user}> who sent you:
                 |\t:heart: ${default} :heart:${end}""".stripMargin
            )
            q.dequeue
          }
        }
      }

    case SlashRelay(msg) =>
      Love(msg).map(self.forward(_))
      // Crush(msg).map(self.forward(_))
      // Guess(msg).map(self.forward(_))

    case msg: Message =>
      Love(msg).map(self.forward(_))

    case crush: Crush =>
      log.info(s"request crush ${crush}")
      self.forward(Love(crush.msg, crush.targets, crush.reason, crush.volume))

    case love: Love =>
      log.info(s"request love ${love}")
      val msg = love.msg
      for {
        userslist <- V2D2.users
      } yield {
        val nmap = userslist.map(u => u.id -> u).toMap
        val emap = userslist.map { u =>
          val email = u.profile match {
            case Some(p) =>
              p.email.getOrElse(u.id)
            case _ => u.id
          }
          email -> u
        }.toMap
        nmap.get(love.msg.user) match {
          case Some(user) =>
            val sender = user
            log.info("sending love!!")
            val users = love.targets.flatMap { target =>
              nmap.get(target.trim) match {
                case Some(user) if user.id == V2D2.selfId =>
                  //snappy comeback plus send love
                  if (love.targets.length == 1)
                    context.parent ! EphemResponse(
                      msg,
                      s"<@${msg.user}> umm this is awkward... I am incapable of expressing emotions"
                    )
                  Nil
                case Some(user) if user.id == sender.id =>
                  if (love.targets.length == 1) {
                    context.parent ! EphemResponse(
                      msg, s"<@${msg.user}> loving yourself is healthy... but let's try and share the love")
                  } else {
                    context.parent ! EphemResponse(
                      msg, s"<@${msg.user}> so clever hiding your nick in the list I almost didn't see it... I'll just send love to the others for you.")
                  }
                  Nil
                case Some(user) =>
                  log.info(s"get target ${user}")
                  List(user)
                case _ =>
                  log.info(s"can't find any users in the list")
                  context.parent ! EphemResponse(msg, s"Nice try silly human.")
                  Nil
              }
            }
            if (users.length > 0)
              self.forward(SendUsersLove(sender, users, love.reason, msg, love.volume))
          case _ =>
            context.parent ! Response(msg, s"..sigh humans.")
        }
      }

    case love: SendUsersLove =>
      if (love.lovable) {
        val rlove = RallyLove(
          love.userEmail,
          love.targetsEmails,
          loving(love.message)
        )
        val content = for {
          request <- Marshal(rlove).to[RequestEntity]
          response <- Http().singleRequest(
            HttpRequest(method = HttpMethods.POST, uri = V2D2.loveurl, entity = request)
          )
          entity <- Unmarshal(response.entity).to[String]
        } yield entity
        content.onComplete {
          case Success(sendLoves) =>
            self ! sendLoves
            log.info("LOVE SENT")
            val targets = love.recipients.map { usr =>
              usr.id
            } //s"<@${usr.id}>" }
            val nicks = love.recipients.map { usr =>
              s"<@${usr.id}>"
            }
            if (love.volume == 0) {
              targets.map { nick =>
                guessing
                  .getOrElseUpdate(
                    nick,
                    Queue()
                  )
                  .enqueue(GuessLove(love.msg, 0))
                context.parent !
                  EphemResponse(
                    received = Message(
                      ts = "1234",
                      user = nick,
                      channel = love.msg.channel,
                      text = love.msg.text,
                      is_starred = None,
                      thread_ts = None
                    ),
                    deliver =
                      s":heart: ${love.message.getOrElse("something random")} :heart:\n\t`/guess [@username]` to guess who your crush is\n\t"
                  )
              }
              context.parent !
                EphemResponse(
                  love.msg,
                  s":heart: Crush has been sent to ${nicks.mkString(" ")} :heart:\n\t${love.message.getOrElse("something random")}"
                )
            } else if (love.volume == 1) {
              targets.map { usr =>
                context.parent !
                  EphemResponse(
                    received = Message(
                      ts = "1234",
                      user = usr,
                      channel = love.msg.channel,
                      text = love.msg.text,
                      is_starred = None,
                      thread_ts = None
                    ),
                    deliver =
                      s":heart: ${love.message.getOrElse("something random")} :heart:\n\t-<@${love.msg.user}> "
                  )
              }
              context.parent !
                EphemResponse(
                  love.msg,
                  s"Love has been sent to ${nicks.mkString(" ")}\n\t${love.message.getOrElse("something random")}"
                )
            } else {
              context.parent !
                Response(
                  love.msg,
                  s":heart: Love has been sent to ${nicks.mkString(" ")} :heart:\n\t${love.message
                    .getOrElse("something random")}\n\t\t-<@${love.msg.user}>"
                )
            }
          case Failure(t) =>
            context.parent ! Response(love.msg, "An error has occured: " + t.getMessage)
        }
      }
    case _ => None
  }
}
