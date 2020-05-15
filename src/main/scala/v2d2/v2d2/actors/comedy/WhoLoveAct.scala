package v2d2.actions.love

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorContext, ActorLogging, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import v2d2.V2D2
import slack.models.Message
import scala.concurrent.ExecutionContext.Implicits.global
import v2d2.protocols.Response
import v2d2.protocols.EphemResponse
import slack.models.User

class WhoLoveAct extends Actor with ActorLogging with LoveListJPTL with LoveResultJPTL {

  implicit val system       = ActorSystem("slack")
  implicit val materializer = ActorMaterializer()
  implicit val timeout      = Timeout(25.seconds)

  def formatOutput(
    idMap: Map[String, Int]
  ) = {
    Future.sequence {
      idMap.map {
        case (k, v) =>
          for {
            userlist <- V2D2.users
          } yield {
            val names = userlist.collect {
              case u: User if u.profile != None && u.profile.get.email != None =>
                u.profile.get.email.get -> u
            }.toMap
            val user = names.get(k) match {
              case Some(u) => Some(u)
              case None    => names.get(k + "@rallyhealth.com")
            }
            user.map { u =>
              val start = s"\t${u.profile.get.real_name.getOrElse("missing")} $v time"
              val end = if (v > 1) {
                "s"
              } else ""
              start + end
            }
          }
      }
    }
  }

  def receive: Receive = {

    case love: GetUsersSentLove =>
      if (love.lovable) {
        val target = love.userEmail.replaceAll("@.*$", "")
        val uri    = s"${V2D2.loveurl}/${target}"
        log.info(s"request love for ${uri}")
        val content = for {
          response <- Http().singleRequest(
            HttpRequest(method = HttpMethods.GET, uri = s"${V2D2.loveurl}/${target}")
          )
          entity <- Unmarshal(response.entity).to[LoveList]
        } yield entity
        content.onComplete {
          case Success(data) =>
            log.info(s"output ${data.sent}")
            if (data.sent.length == 0) {
              context.parent ! Response(love.msg, s"<@${love.user.id}> has a heart of stone")
            } else {

              val foo = data.sent.flatMap(r => r.recipients).groupBy(i => i).map {
                case (k, v) => k -> v.size
              }
              Future
                .sequence(
                  data.sent
                    .flatMap(r => r.recipients)
                    .groupBy(i => i)
                    .map {
                      case (k, v) => k -> v.size
                    }
                    .map {
                      case (k, v) =>
                        for {
                          userlist <- V2D2.users
                        } yield {
                          val names = userlist.collect {
                            case u: User if u.profile != None && u.profile.get.email != None =>
                              u.profile.get.email.get -> u
                          }.toMap
                          val user = names.get(k) match {
                            case Some(u) => Some(u)
                            case None    => names.get(k + "@rallyhealth.com")
                          }
                          user.map { u =>
                            val start = s"\t${u.profile.get.real_name.getOrElse("missing")} $v time"
                            val end = if (v > 1) {
                              "s"
                            } else ""
                            start + end
                          }
                        }
                    }
                )
                .map { strs =>
                  context.parent ! EphemResponse(
                    love.msg,
                    s":heart: <@${love.user.id}> loves :heart:\n" +
                      strs
                        .collect {
                          case Some(u) => u
                        }
                        .mkString("\n")
                  )
                }

            }
          case Failure(t) =>
            context.parent ! Response(love.msg, s"An error has occured: " + t.getMessage)
        }
      }

    case love: GetUsersLove =>
      if (love.lovable) {
        val target = love.userEmail.replaceAll("@.*$", "")
        val uri    = s"${V2D2.loveurl}/${target}"
        log.info(s"request love for ${uri}")
        val content = for {
          response <- Http().singleRequest(
            HttpRequest(method = HttpMethods.GET, uri = s"${V2D2.loveurl}/${target}")
          )
          entity <- Unmarshal(response.entity).to[LoveList]
        } yield entity
        content.onComplete {
          case Success(data) =>
            log.info(s"output ${data.received}")
            if (data.received.length == 0) {
              context.parent ! EphemResponse(
                love.msg,
                s"<@${love.senderNick}> oh boy this is awkward... you don't have any love yet"
              )
            } else {
              for {
                userlist <- V2D2.users
              } yield {
                val emailmap = userlist.collect {
                  case u: User if u.profile != None && u.profile.get.email != None =>
                    u.profile.get.email.get -> u
                }.toMap
                val details = data.received
                  .map(r => r.sender)
                  .groupBy(i => i)
                  .map {
                    case (k, v) => k -> v.size
                  }
                  .flatMap {
                    case (k, v) =>
                      val user = emailmap.get(k) match {
                        case Some(u) => Some(u)
                        case None    => emailmap.get(k + "@rallyhealth.com")
                      }
                      user.map { u =>
                        val start = s"\t${u.profile.get.real_name.getOrElse("missing")} $v time"
                        val end = if (v > 1) {
                          "s"
                        } else ""
                        start + end
                      }
                    // }.map {
                    //   case Some(str) => str
                    //   case None => "
                  }
                  .mkString("\n")
                context.parent ! EphemResponse(
                  love.msg,
                  s":heart: <@${love.user.id}> has received love from :heart:\n" +
                    details
                )
              }
            }
          case Failure(t) =>
            context.parent ! Response(love.msg, s"An error has occured: " + t.getMessage)
        }
      }

    case msg: Message =>
      WhoDoLove(msg) match {
        case Some(whodo) =>
          log.info(s"entering whodo")
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
            nmap.get(whodo.msg.user) match {
              case Some(user) =>
                val mark = whodo.target.trim()
                log.info(s"user: $user")
                if (mark.equalsIgnoreCase("i")) {
                  self ! GetUsersSentLove(user, user.id, whodo.msg)
                } else {
                  log.info(s"WELL THIS whodo ${mark}")
                  nmap.get(mark) match {
                    case Some(mark) =>
                      self ! GetUsersSentLove(mark, user.id, whodo.msg)
                    case _ =>
                      val email = if (mark.contains(".")) {
                        mark + "@rallyhealth.com"
                      } else if (mark.split(' ').length == 2) {
                        mark.split(' ').mkString(".") + "@rallyhealth.com"
                      } else {
                        "noreply@rallyhealth.com"
                      }
                      emap.get(email) match {
                        case Some(mark) =>
                          self ! GetUsersSentLove(mark, user.id, whodo.msg)
                        case _ =>
                          context.parent ! Response(
                            whodo.msg,
                            s"I couldn't find ${mark}.. do they exist? Do I exist?"
                          )
                      }
                  }
                }
              case _ =>
                context.parent ! Response(whodo.msg, s"Nice try silly human.")
            }
          }

        case _ => None
      }

      WhoLove(msg) match {
        case Some(who) =>
          log.info("entering who love")
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
            nmap.get(who.msg.user) match {
              case Some(user) =>
                if (who.target.equalsIgnoreCase("me")) {
                  log.info("who talk to yourself")
                  self ! GetUsersLove(user, user.id, who.msg)
                } else {
                  nmap.get(who.target.trim) match {
                    case Some(mark) =>
                      self ! GetUsersLove(mark, user.id, who.msg)
                    case _ =>
                      context.parent ! Response(who.msg, s"Nice try silly human.")
                  }
                }
              case _ =>
                context.parent ! Response(who.msg, s"Nice try silly human.")
            }
          }
        case _ => None
      }
  }
}
