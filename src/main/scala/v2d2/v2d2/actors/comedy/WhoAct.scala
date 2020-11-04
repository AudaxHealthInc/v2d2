package v2d2.actions.who

import scala.collection.immutable
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
import org.apache.commons.lang3.StringUtils
import v2d2.V2D2
import v2d2.protocols.Response
import slack.models.Message
import scala.concurrent.ExecutionContext.Implicits.global
import slack.models.User
import slack.rtm.SlackRtmClient
import v2d2.protocols.SlashRelay
import v2d2.protocols.EphemResponse
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.util.ByteString

class WhoAct extends Actor with ActorLogging with WhoJPTL {

  implicit val system  = ActorSystem("slack")
  implicit val timeout = Timeout(25.seconds)

  def best(
    s: String,
    person: WhoUser
  ): Int = {
    List(
      StringUtils.getLevenshteinDistance(person.last.toLowerCase(), s),
      StringUtils.getLevenshteinDistance(person.first.toLowerCase(), s),
      StringUtils.getLevenshteinDistance(person.gitHubUsername.getOrElse("").toLowerCase(), s),
      StringUtils.getLevenshteinDistance(person.hipchatMention.getOrElse("").toLowerCase(), s)
    ).min
  }

  def lookup(
    search: String,
    people: Seq[WhoUser],
    nickMap: Map[String, User],
    emailMap: Map[String, User]
  ): Tuple2[Int, Seq[WhoUser]] = {
    log.info("in lookup")
    // first we look for literal matches on full name, first name, gitHubUsername
    // we already checked literal matches of user.name (email), first.last and hipchatmention
    // then we need to figure out the format of the string
    // "user.name" => check email filed
    // "first last" => check the name field
    // "someString" => we will just default for this ie _
    //    Then we will need to be createive here:
    //      check first name, githubusername, hipchatMention
    val uname = s"([^\\.]*\\.[^\\.]*)".r
    val fname = s"([^\\.]*\\s[^\\.]*)".r
    people.filter(
      p =>
        p.first.equalsIgnoreCase(search) ||
          p.last.equalsIgnoreCase(search) ||
          p.name.equalsIgnoreCase(search) ||
          p.gitHubUsername.getOrElse("").equalsIgnoreCase(search)
    ) match {
      case Nil =>
        log.info("in lookup NIL")
        search match {
          case uname(n) =>
            log.info("in lookup UNAME")
            // group by distance sort by distance take the closest list
            val out = people
              .groupBy(
                p => StringUtils.getLevenshteinDistance(p.email.toLowerCase(), n.toLowerCase())
              )
              .toList
              .sortBy(_._1)
              .head
            out
          case fname(n) =>
            log.info("in lookup fullname")
            people
              .groupBy(
                p => StringUtils.getLevenshteinDistance(p.name.toLowerCase(), n.toLowerCase())
              )
              .toList
              .sortBy(_._1)
              .head
          case _ =>
            // check first name, githubusername, hipchatMention
            log.info("in lookup default")
            people.groupBy(p => best(search.toLowerCase(), p)).toList.sortBy(_._1).head
        }
      case p =>
        Tuple2(0, p)
    }
  }

  def genResponse(
    msg: Message,
    data: List[WhoUser],
    silent: Boolean
  ): v2d2.protocols.Responder = {
    if (data.length > 4) {
      val str = data
        .map { u =>
          s"Name: ${u.name}"
        }
        .mkString("\n")
      EphemResponse(msg, str)
      // if(silent) EphemResponse(msg, str)
      // else Response(msg, str)
    } else {
      val str = data
        .map { e =>
          e.avatar.getOrElse("https://who.werally.in/images/avatar/anon.svg")
        }
        .mkString("\n")
      EphemResponse(msg, str)
      // if(silent) EphemResponse(msg, str)
      // else Response(msg, str)
    }
  }

  def receive: Receive = {
    case who: GetWhoUser =>
      val target = (who.target.profile match {
        case Some(p) =>
          p.email.getOrElse(
            p.first_name.getOrElse("empty") + "." + p.last_name.getOrElse("empty")
          )
        case _ =>
          "empty"
      }).replaceAll("@.*$", "")
      val uri = s"${V2D2.whourl}/people/${target}"
      log.info(s"request love for ${uri}")
      pprint.log("get who user")
      val content = for {
        response <- Http().singleRequest(
          HttpRequest(
            method = HttpMethods.GET,
            uri = s"${V2D2.whourl}/people/${target}"
          ).withHeaders(List(`Content-Type`(ContentTypes.`application/json`)))
        )
        entity <- Unmarshal(response.entity).to[WhoUser]
      } yield entity
      content.onComplete {
        case Success(data) =>
          context.parent ! genResponse(who.msg, List(data), who.silent)
        case Failure(t) =>
          context.parent ! Response(who.msg, s"An error has occured: " + t.getMessage)
      }

    case who: GetWhoAll =>
      val request = HttpRequest(
        method = HttpMethods.GET, 
        uri = s"${V2D2.whourl}/people",
        entity = HttpEntity(ContentTypes.`application/json`, ByteString.empty)
      )
      val content = for {
        response <- Http().singleRequest(request
          // HttpRequest(method = HttpMethods.GET, uri = s"${V2D2.whourl}/people").withEntity(
          //   ContentTypes.`application/json`, ""
          //   )
            // .withHeaders(List(`Content-Type`(ContentTypes.`application/json`)))
            // HttpRequest.entity.contentType
        )
        entity <- Unmarshal(response.entity).to[Seq[WhoUser]]
      } yield entity
      content.onComplete {
        case Success(data) =>
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
            context.parent ! genResponse(
              who.msg,
              lookup(who.search, data, nmap, emap)._2.toList,
              who.silent
            )
          }
        case Failure(t) =>
          context.parent ! Response(who.msg, s"An error has occured: " + t.getMessage)
      }

    case SlashRelay(msg) =>
      WhoIs(msg).map(x => self.forward(x.copy(silent = true)))

    case msg: Message =>
      WhoIs(msg).map(self.forward(_))

    case who: WhoIs =>
      val msg = who.msg
      log.info("entering who is")
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
        nmap.get(who.target) match {
          case Some(user) =>
            log.info(s"who is ${who.target}")
            self ! GetWhoUser(msg, user, who.silent)
          case _ =>
            emap.get(who.target + "@rallyhealth.com") match {
              case Some(user) =>
                self ! GetWhoUser(msg, user, who.silent)
              case _ => self ! GetWhoAll(msg, who.target, who.silent)
            }
        }
      }
  }
}
