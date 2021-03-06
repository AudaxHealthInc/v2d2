package v2d2

import slack.rtm.SlackRtmClient
import slack.api.SlackApiClient
import akka.actor.ActorSystem
import akka.actor.{Actor, ActorContext, ActorLogging, ActorSystem, Props}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import slack.SlackUtil
import com.typesafe.config.ConfigFactory
import v2d2.protocols.Relay
import com.softwaremill.macwire._
import com.softwaremill.macwire.akkasupport._
import com.softwaremill.tagging._
import v2d2.actors.core.MuxActor
import v2d2.actors.core.EmoActor
import slack.models.ReactionAdded
import v2d2.protocols.EmoRelay
import slack.models.ReactionRemoved
import slack.models.Reply
import v2d2.protocols.Ephemeral
import v2d2.protocols.EphemResponse
import slack.models.Message
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import v2d2.protocols._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller

object V2D2 extends App with SlashCommandProtocol {
  val creds   = ConfigFactory.load("creds.conf")
  val uris    = ConfigFactory.load("v2d2.conf")
  val ptoken  = creds.getString("creds.slack.ptoken")
  val btoken  = creds.getString("creds.slack.btoken")
  val whourl  = uris.getString("v2d2.who.url")
  val loveurl = uris.getString("v2d2.love.url")

  implicit val system       = ActorSystem("slack")
  implicit val ec           = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val client   = SlackRtmClient(btoken)
  val muxactor = system.actorOf(Props(new MuxActor(client)), name = "mux")
  val emoactor = system.actorOf(Props(new EmoActor(client)), name = "emo")

  val selfId = client.state.self.id

  val users = client.apiClient.client.listUsers()

  val apiclient = SlackApiClient(ptoken)
  client.onMessage { message =>
    muxactor ! Relay(message)
  }

  //TODO: make this more generic?
  lazy val routes: Route =
    concat(
      pathPrefix("guess") {
        concat(
          pathEnd {
            concat(
              post {
                formFieldMap { fields =>
                  val ocmd = SlashCommand(fields)
                  ocmd.map { cmd =>
                    muxactor ! SlashRelay(
                      Message(
                        ts = "1234",
                        channel = cmd.channel_id,
                        user = cmd.user_id,
                        text = cmd.strippedText,
                        is_starred = None,
                        thread_ts = None
                      )
                    )
                  }
                  complete(StatusCodes.NoContent)
                }
              }
            )
          }
        )
      },
      pathPrefix("who") {
        concat(
          pathEnd {
            concat(
              post {
                formFieldMap { fields =>
                  val ocmd = SlashCommand(fields)
                  ocmd.map { cmd =>
                   muxactor ! SlashRelay(
                      Message(
                        ts = "1234",
                        channel = cmd.channel_id,
                        user = cmd.user_id,
                        text = cmd.strippedText,
                        is_starred = None,
                        thread_ts = None
                      )
                    )
                  }
                  complete(StatusCodes.NoContent)
                }
              }
            )
          }
        )
      },
      // pathPrefix("crush") {
      //   concat(
      //     pathEnd {
      //       concat(
      //         post {
      //           formFieldMap { fields =>
      //             val ocmd = SlashCommand(fields)
      //             ocmd.map { cmd =>
      //               muxactor ! SlashRelay(
      //                 Message(
      //                   ts = "1234",
      //                   channel = cmd.channel_id,
      //                   user = cmd.user_id,
      //                   text = cmd.strippedText,
      //                   is_starred = None,
      //                   thread_ts = None
      //                 )
      //               )
      //             }
      //             complete(StatusCodes.NoContent)
      //           }
      //         }
      //       )
      //     }
      //   )
      // },
      pathPrefix("love") {
        concat(
          pathEnd {
            concat(
              post {
                formFieldMap { fields =>
                  val ocmd = SlashCommand(fields)
                  ocmd.map { cmd =>
                    muxactor ! SlashRelay(
                      Message(
                        ts = "1234",
                        channel = cmd.channel_id,
                        user = cmd.user_id,
                        text = cmd.strippedText,
                        is_starred = None,
                        thread_ts = None
                      )
                    )
                  }
                  complete(StatusCodes.NoContent)
                }
              }
            )
          }
        )
      }
    )

  client.onEvent {
    case reply: Reply =>
      emoactor ! reply
    case react: ReactionRemoved =>
      emoactor ! react
    case react: ReactionAdded =>
      emoactor ! react
    case x =>
      None

  }
  val bindingFuture = Http().bindAndHandle(routes, "localhost", 8081)
}
