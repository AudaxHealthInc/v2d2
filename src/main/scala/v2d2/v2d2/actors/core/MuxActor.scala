package v2d2.actors.core

import slack.rtm.SlackRtmClient
import akka.actor.{Actor, ActorContext, ActorLogging, Props}
import slack.SlackUtil
import v2d2.protocols._
import com.softwaremill.macwire._
import akka.actor.{ActorRef, ActorSystem, Props}
import com.softwaremill.macwire.akkasupport._
import scala.concurrent.Future
import com.softwaremill.tagging._
import v2d2.actors.comedy.Knocker
import v2d2.mtg.MagicAct
import v2d2.V2D2

import slack.api.SlackApiClient
import v2d2.actions.who.WhoAct
import v2d2.actions.love.LoveAct
import v2d2.actions.love.WhoLoveAct

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import v2d2.protocols.Ephemeral
import scala.concurrent.ExecutionContext.Implicits.global

import akka.util.Timeout
import akka.stream.ActorMaterializer

import scala.concurrent.duration._
import scala.util.Success
import scala.util.Failure
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import slack.models.Attachment

class MuxActor(
  client: SlackRtmClient,
  apiclient: SlackApiClient
) extends Actor
  with EphemeralProtocol
  with ActorLogging {

  val selfId           = client.state.self.id
  implicit val system  = ActorSystem("slack")
  implicit val timeout = Timeout(25.seconds)

  context.actorOf(Props(classOf[Knocker]), name = "knocker")
  context.actorOf(Props(classOf[MagicAct]), name = "magiccards")
  context.actorOf(Props(classOf[WhoAct]), name = "whoact")
  context.actorOf(Props(classOf[LoveAct]), name = "loveact")
  context.actorOf(Props(classOf[WhoLoveAct]), name = "wholoveact")

  def receive: Receive = {

    case SimpleResponse(channel, txt) =>
      client.sendMessage(channel, txt)

    // Unused
    case SimpleEphemResponse(channel, txt, user) =>
      apiclient.postChatEphemeral(
        channelId = channel,
        text = txt,
        user = user
      )

    // case EphemResponse(rec, txt) =>

    case EphemResponse(rec, txt) =>
      // TODO fix this
      if (txt.endsWith(".jpg")) {
        apiclient.postChatEphemeral(
          channelId = rec.channel,
          text = txt,
          attachments = Some(
            List(
              Attachment(
                fallback = Some("fallback"),
                image_url = Some(txt)
              )
            )
          ),
          user = rec.user
        )
      } else {
        apiclient.postChatEphemeral(
          channelId = rec.channel,
          text = txt,
          user = rec.user
        )
      }

    case CardResponse(rec, txt, cards) =>
      client.sendMessage(rec.channel, txt)

    case Response(rec, txt) =>
      client.sendMessage(rec.channel, txt)

    case tj: TrackJoke =>
      context.actorSelection("/user/emo") ! tj

    case sr: SlashRelay =>
      if (sr.msg.text == "") {
        None
      } else {
        context.children.foreach { child =>
          child ! sr
        }
      }

    case Relay(msg) =>
      if (msg.text == "") {
        None
      } else if (msg.text == "vito rocks") {
        self ! EphemResponse(msg, "all hail the creator")
      } else
        context.children.foreach { child =>
          child ! msg
        }

    case x => pprint.log(x)
  }
}
