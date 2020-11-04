package v2d2.protocols

import slack.models.Message
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import scala.util.control.NonFatal
import v2d2.mtg.ICard

trait Responder {
  def received: Message
  def deliver: String

  def channel: String = {
    received.channel
  }

  def timestamp: String = {
    f"${System.currentTimeMillis.toDouble / 1000}%.6f"
  }
}
case class KnockJoke(
  clue: String,
  ans: String
)
case class SlashRelay(
  msg: Message
)
case class Relay(
  msg: Message
)
case class Response(
  received: Message,
  deliver: String
) extends Responder

case class CardResponse(
  received: Message,
  deliver: String,
  cards:List[ICard]
) extends Responder

case class EphemResponse(
  received: Message,
  deliver: String
) extends Responder
case class SimpleResponse(
  channel: String,
  deliver: String
)
case class SimpleEphemResponse(
  channel: String,
  deliver: String,
  user: String
)

case class History(
  channel: String,
  latest: Option[String] = None,
  oldest: Option[String] = None,
  inclusive: Option[Int] = None,
  count: Option[Int] = None
)
case class SetupTracker(
  guess: String,
  item: TrackJoke
)
case class TrackJoke(
  received: Message,
  deliver: String,
  joke: KnockJoke
) extends Responder
case class TrackedJoke(
  joke: KnockJoke,
  votes: Votes
)
case class Votes(
  up: Int,
  down: Int,
  unknown: Int,
  emojis: Map[String, Int]
)

object Votes {

  def vote(
    emoji: String
  ): Int = {
    if (emoji == "+1" || emoji.contains("up") || emoji.contains("lgtm")) { // fine for now
      1
    } else if (emoji == "-1" || emoji.contains("down")) { // fine for now
      -1
    } else {
      0
    }
  }
}

trait EphemeralProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val format = jsonFormat6(Ephemeral.apply)
}
object EphemeralProtocol extends EphemeralProtocol
case class Ephemeral(
  channelId: String,
  text: String,
  user: String,
  asUser: Option[Boolean] = None,
  parse: Option[String] = None,
  linkNames: Option[Boolean] = None
)

// relay your own message for tracking
case class EmoRelay(
  watched: Message
)

case class SlashCommand(
  token: String,
  team_id: String,
  team_domain: String,
  channel_id: String,
  channel_name: String,
  user_id: String,
  user_name: String,
  command: String,
  text: String,
  response_url: String,
  trigger_id: Option[String] = None,
  enterprise_id: Option[String] = None,
  enterprise_name: Option[String] = None
) {

  def strippedText = {
    (command + " " + text)
      .replaceAll("(<@[^|]*)\\|[^>]*(>)", "$1$2")
      .replaceAll("^/", "")

  }
}

trait SlashCommandProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val format = jsonFormat13(SlashCommand.apply)
}
object SlashCommandProtocol extends SlashCommandProtocol

// token= TzcC7LDYX3Hs20Bs4kVgqPsb
// team_id= TGQ52N5PG
// team_domain= rallyhealthqa
// channel_id= CML9J1N9G
// channel_name= bot-test
// user_id= UM3ETMMPE
// user_name= vito.cutten
// command= %2Flove
// text= %3C%40UM3ETMMPE%7Cvito.cutten%3E+blash
// response_url= https%3A%2F%2Fhooks.slack.com%2Fcommands%2FTGQ52N5PG%2F816345947607%2Fxmtvxd3t5NqX6IOxXhzQPnIK
// trigger_id= 816698866726.568172753798.ee92f40b96b4a29a9d4c881e70d0i^C4
//
object SlashCommand {

  def apply(
    values: Map[String, String]
  ): Option[SlashCommand] = {
    try {
      Some(
        SlashCommand(
          token = values("token"),
          team_id = values("team_id"),
          team_domain = values("team_domain"),
          enterprise_id = values.get("enterprise_id"),
          enterprise_name = values.get("enterprise_name"),
          channel_id = values("channel_id"),
          channel_name = values("channel_name"),
          user_id = values("user_id"),
          user_name = values("user_name"),
          command = values("command"),
          text = values("text"),
          response_url = values("response_url"),
          trigger_id = values.get("trigger_id ")
        )
      )
    } catch {
      case NonFatal(ex) =>
        pprint.log(ex)
        None
    }
  }
}
