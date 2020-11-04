package v2d2.mtg

import java.io.InputStream

import org.apache.commons.text.similarity._
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorContext, ActorLogging, ActorSystem}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.apache.commons.lang3.StringUtils
import slack.models.Message
import scala.concurrent.ExecutionContext
import v2d2.protocols.{CardResponse, Response}
import akka.http.scaladsl.model.HttpResponse

class MagicAct extends Actor with ActorLogging with CardSetProtocol {
  implicit val ec = ExecutionContext.global
  // import system.dispatcher
  implicit val system  = ActorSystem()
  implicit val timeout = Timeout(30.seconds)


  val stream: InputStream = getClass.getResourceAsStream("/allsets.json")
  val json                = scala.io.Source.fromInputStream(stream).mkString
  // val stream: InputStream = getClass.getResourceAsStream("/allsets.json")
  // val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, stream))
  // val sjson               = scala.io.Source.fromInputStream(stream)
  // val json                = scala.io.Source.fromInputStream(stream).mkString

  def scores(
    str: String,
    search: String
  ): List[Int] = {
    val symbols = """!<'_-&^'%$#"@=>,""".flatMap(s => s + "|")
    val p       = s"(\\.|\\*|${symbols})"
    str
      .replaceAll(p, "")
      .toLowerCase()
      .split(" ")
      .map { w =>
        search
          .replaceAll(p, "")
          .split(" ")
          .map { s =>
            StringUtils.getLevenshteinDistance(w, s)
          }
          .sorted
          .head
      }
      .toList
      .sorted
  }

  case class Score(
    min: Int,
    minCount: Int,
    raw: List[Int]
  )

  def lookupName(
    search: String,
    cards: List[ICard]
  ): List[ICard] = {
    cards.filter(c => c.name.equalsIgnoreCase(search)) match {
      case Nil =>
        val minScores = cards
          .filterNot(_.image_uris == None)
          .map { c =>
            val raw = scores(c.name, search)
            val m   = raw.min
            val ct  = raw.count(_ == m)
            (Score(m, ct, raw), c)
          }
          .groupBy { i =>
            Score(i._1.min, i._1.minCount, List())
          }
          .toList
          .sortWith {
            case (t1, t2) =>
              val score1 = t1._1
              val score2 = t2._1
              if (score1.min == score2.min) {
                score1.minCount > score2.minCount
              } else {
                score1.min < score2.min
              }
          }
          .head
        // make the list unique by name
        minScores._2
          .map(_._2)
          .groupBy { c =>
            c.name
          }
          .map { t =>
            t._2.head
          }
          .toList
      case c =>
        Tuple2(0, c)._2.groupBy(c => c.name).map(t => t._2.head).toList
    }
  }

  def receive: Receive = {
    // case scry:
    case mc: MagicCards =>
      val req = for {
        cards <- Unmarshal(json).to[List[Card]]
      } yield (
        cards
      )
      req.pipeTo(sender)

    case cs: CardNameSearch =>
      val content = for {
        cards <- (self ? MagicCards()).mapTo[List[Card]]
      } yield cards
      content.onComplete {
        case Success(cards) =>
          val target  = cs.target.toLowerCase()
          val results = lookupName(target, cards)
          val score   = scores(results.head.name, cs.target.toLowerCase()).min
          val tlen    = cs.target.length
          val pcent   = (tlen - score).toFloat / tlen

          // println("++++++++++++++++++++++++++++")
          // println(s"pc: ${pcent} score: ${score} len: ${tlen}")
          // println(s"len: ${cs.target.length} target: ${cs.target}")
          // println("++++++++++++++++++++++++++++")

          val jw    = new JaroWinklerDistance()
          val jcent = jw(target, results.head.name.toLowerCase)
          val p     = "%"
          if (cs.target.length < 3) {
            context.parent ! Response(cs.msg, "Try asking again with a longer string")
          } else if (((tlen == 3 || tlen == 4) && score > 1) ||
                     ((tlen == 5 || tlen == 6) && score > 2) || pcent < 0.7) {
            context.parent ! CardResponse(
              cs.msg,
              f"""/shrug your best match was
                 |${results.head.name} with ${pcent * 100}%1.2f$p
                 |and score ${jcent * 100}%1.2f$p""".stripMargin.replaceAll("\n", " "),
              results
            )
          } else {

            val imgs = results.collect {
              case c if (c.image_uris != None) =>
                val u = c.image_uris.get.png.replaceAll("\\?.*$", "")
                (u -> c)
            }

            if (imgs.length > 16) {
              context.parent ! CardResponse(
                cs.msg,
                f"""Found too many matches (${imgs.length}). The best match was
                   |${results.head.name} with ${pcent * 100}%1.2f$p
                   |and score ${jcent * 100}%1.2f$p""".stripMargin.replaceAll("\n", " "),
                results
              )
            } else {
              context.parent ! CardResponse(
                cs.msg,
                f"""The best match was ${results.head.name} with ${pcent * 100}%1.2f$p
                   |and score ${jcent * 100}%1.2f$p
                   |${imgs.head._1}""".stripMargin.replaceAll("\n", " "),
                results
              )
            }
          }
        case Failure(t) =>
          context.parent ! Response(cs.msg, s"An error has occured: " + t.getMessage)
      }

    case msg: Message =>
      CardNameSearch(msg) match {
        case Some(cs) =>
          self.forward(cs)
        case _ =>
          None
      }
    case _ => None
  }

}
