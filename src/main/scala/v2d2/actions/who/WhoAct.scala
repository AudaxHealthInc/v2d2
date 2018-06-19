package v2d2.actions.who
import v2d2.actions.generic.HipNotif

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
import v2d2.actions.generic.protocol.Response
import v2d2.client.{IMessage, User}
import v2d2.client.core._

class WhoAct(room: Option[String])
extends Actor 
with ActorLogging 
with WhoJPTL {

  import system.dispatcher
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(25.seconds)

  def best(s: String, person: WhoUser): Int = {
    List(
      StringUtils.getLevenshteinDistance(
        person.last.toLowerCase(), s),
      StringUtils.getLevenshteinDistance(
        person.first.toLowerCase(), s),
      StringUtils.getLevenshteinDistance(
        person.gitHubUsername.getOrElse("").toLowerCase(), s),
      StringUtils.getLevenshteinDistance(
        person.hipchatMention.getOrElse("").toLowerCase(), s)).min
  }

  def lookup(
    search: String, 
    people: Seq[WhoUser],
    nickMap: Map[String,User],
    emailMap: Map[String,User]
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
    people.filter( p =>
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
              val out = people.groupBy( p =>
                StringUtils.getLevenshteinDistance(
                  p.email.toLowerCase(), n.toLowerCase())
              ).toList.sortBy(_._1).head
              pprint.log(out,"results")
              out
            case fname(n) =>
              log.info("in lookup fullname")
              people.groupBy( p =>
                StringUtils.getLevenshteinDistance(
                  p.name.toLowerCase(), n.toLowerCase())
              ).toList.sortBy(_._1).head
            case _ =>
              // check first name, githubusername, hipchatMention
              log.info("in lookup default")
              people.groupBy( p =>
                  best(search.toLowerCase(), p)
              ).toList.sortBy(_._1).head
          }
        case p => 
          Tuple2(0, p)
      }
  }

  def genResponse(imsg:IMessage, data:List[WhoUser]): Response = {
    if (data.length > 4) {
      Response(
        imsg, data.map { u =>
          s"Name: ${u.name}"
        }.mkString("\n"), None)
    } else {
      val h = if(data.size>4) 128 else 256
      val body = data.map( e => 
          s"""<tr>""" +
          s"""<td><img src="${
            e.avatar.getOrElse("https://who.werally.in/images/avatar/anon.svg")}" height="${h}"</td>""" + 
          s"""<td>Name: ${e.name}<BR>""" +
          s"""Email: ${e.email}<BR>""" +
          s"""Location: ${e.loc.getOrElse("Top Secret")}<BR>""" +
          s"""</td>""" + 
          s"""</tr>""".stripMargin
          )
      val out = s"<table>${body.mkString("")}</table>"
      Response(imsg, out, Some(HipNotif("gray","html",out, room.getOrElse("120"))))
    }
  }

  def receive: Receive = {
    case who: GetWhoUser =>
      val target = who.target.email.replaceAll("@.*$","")
      val uri = s"${V2D2.who("url")}/people/${target}"
      log.info(s"request love for ${uri}")
      val content = for {
        response <- Http().singleRequest(
          HttpRequest(
            method = HttpMethods.GET,
            uri = s"${V2D2.who("url")}/people/${target}"))
        entity <- Unmarshal(response.entity).to[WhoUser]
      } yield entity
      content onComplete {
        case Success(data) =>
          context.parent ! genResponse(who.imsg, List(data))
        case Failure(t) =>
          context.parent ! Response(who.imsg, s"An error has occured: " + t.getMessage, None)
      }

    case who: GetWhoAll =>
      val content = for {
        response <- Http().singleRequest(
          HttpRequest(
            method = HttpMethods.GET,
            uri = s"${V2D2.who("url")}/people"))
        entity <- Unmarshal(response.entity).to[Seq[WhoUser]]
      } yield entity
      content onComplete {
        case Success(data) =>
          for {
            emap <- (context.actorSelection("/user/xmpp") ? EmailMap()).mapTo[Map[String,User]]
            nmap <- (context.actorSelection("/user/xmpp") ? NickMap()).mapTo[Map[String,User]]
          } yield {
            context.parent ! genResponse(who.imsg, lookup(who.search, data, nmap, emap)._2.toList)
          }
        case Failure(t) =>
          context.parent ! Response(who.imsg, s"An error has occured: " + t.getMessage, None)
      }

    // Loop back
    case imsg: IMessage =>
      WhoIs(imsg) match {
        case Some(who) =>
          log.info("entering who is")
          for {
            emap <- (context.actorSelection("/user/xmpp") ? EmailMap()).mapTo[Map[String,User]]
            nmap <- (context.actorSelection("/user/xmpp") ? NickMap()).mapTo[Map[String,User]]
          } yield(
            nmap get (who.target) match {
              case Some(user) =>
                log.info(s"who is ${who.target}")
                self ! GetWhoUser(imsg, user)
              case _ =>
                emap get (who.target + "@rallyhealth.com") match {
                  case Some(user) =>
                    pprint.log(who.target, "who target")
                    self ! GetWhoUser(imsg, user)
                  case _ => self ! GetWhoAll(imsg, who.target)
                }
            })
        case _ => None
      }
      
  }
}
