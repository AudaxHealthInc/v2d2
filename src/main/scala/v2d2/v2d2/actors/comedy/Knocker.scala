package v2d2.actors.comedy

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import scala.concurrent.duration._
import slack.models.Message
import v2d2.protocols.{KnockJoke, Response, TrackJoke}
import scala.util.Random
import v2d2.V2D2

class Knocker extends Actor with ActorLogging {
  implicit val system       = ActorSystem()
  implicit val timeout      = Timeout(25.seconds)

  val answers = Answers.answers
  val clues   = Clues.clues

  var targets: Map[String, Joke]      = Map()
  var responses: Map[String, NewJoke] = Map()

  def receive: Receive = {

    case GetTargets() =>
      sender ! Targets(targets)

    case gr: GetResponses =>
      sender ! Responses(responses)

    case knock: Knockit =>
      val mark = knock.msg.user
      val msg  = knock.msg
      val jokeonme = responses.getOrElse(
        mark,
        NewJoke(
          target = mark,
          state = 0,
          sender = msg.user,
          tries = 0,
          jokeIdx = 0,
          joke = ("" -> "")
        )
      )
      if (jokeonme.state == 0) {
        responses = responses.updated(mark, jokeonme.copy(state = jokeonme.state + 1))
        sender ! Response(knock.msg, s"<@${msg.user}>, who's there?")
      }

    case knock: KnockKnock =>
      val from = knock.msg.user
      val msg  = knock.msg
      knock.target match {
        case Some(target) if target != V2D2.selfId =>
          val joke = targets.getOrElse(
            target,
            Joke(
              target = target,
              state = 0,
              sender = from,
              tries = 0,
              jokeIdx = Random.nextInt(clues.size)
            )
          )
          if (joke.state < 1) {
            targets = targets.updated(target, joke.copy(state = joke.state + 1))
            log.info(s"joke started with ${target}")
            sender ! Response(knock.msg, s"Knock, knock <@${target}>!")
          } else {
            sender ! Response(knock.msg, s"I already have a joke going with, <@${target}>") //retort
          }

        case Some(target) if target == V2D2.selfId =>
          val joke = responses.getOrElse(
            from,
            NewJoke(
              target = from,
              state = 0,
              sender = target,
              tries = 0,
              jokeIdx = 0,
              joke = ("" -> "")
            )
          )

          if (joke.state < 1) {
            Knockit(
              "v2d2, knock knock",
              Message(
                ts = "",
                channel = knock.msg.channel,
                user = knock.msg.user,
                text = "",
                is_starred = None,
                thread_ts = None
              )
            ).map(self.forward(_))
          }
        case _ =>
          sender ! Response(
            knock.msg,
            s"<@${from}> who would you like me to send the joke to?" // retort
          )
      }

    case whois: Whois =>
      val mark = whois.msg.user
      val msg  = whois.msg
      val joke =
        targets.getOrElse(
          mark,
          Joke(target = mark, state = 0, sender = "empty joke", tries = 0, jokeIdx = 0)
        )
      if (joke.state == 1) {
        log.info("someone responded to a joke!!")
        targets = targets.updated(mark, joke.copy(state = joke.state + 1))
        sender ! Response(whois.msg, s"<@${mark}>, ${clues(joke.jokeIdx)}")
      } else if (joke.state > 0) {
        log.info(s"snappy comeback")
        sender ! Response(msg, s"<@${mark}>, you do remember how knock knock jokes work?") // retort
      } else if (targets.nonEmpty) {
        log.info(s"snap two")
        sender ! Response(msg, s"<@${mark}>, shhh don't try to steal jokes...") // retort
      }

    case who: Who =>
      val mark = who.msg.user
      val msg  = who.msg
      val joke =
        targets.getOrElse(
          mark,
          Joke(target = mark, state = 0, sender = "empty joke", tries = 0, jokeIdx = 0)
        )
      if (joke.state == 2) {
        val tries = if (joke.tries > 0) {
          s" You responded ${joke.tries} times incorrectly great job hooman."
        } else {
          ""
        }
        sender ! Response(
          msg,
          s"<@${mark}>, ${answers(joke.jokeIdx)}\n\tJoke sent by: <@${joke.sender}>${tries}"
        )
        targets = targets - mark
      } else if (joke.state > 0) {
        sender ! Response(msg, s"<@${mark}>, do you remember how knock knock jokes work?") // retort
      } else if (targets.nonEmpty) {
        sender ! Response(msg, s"<@${mark}>, shhh don't try to steal jokes...") // retort
      }

    case msg: Message =>
      Knockit(msg).map(self.forward(_))
      KnockKnock(msg).map { k =>
        self.forward(k)
      }
      Whois(msg).map(w => self.forward(w))
      Who(msg).map(w => self.forward(w))
      if ((Whois(msg) == None) && (Who(msg) == KnockKnock(msg))) {
        val mark = msg.user
        // This means we need to check the targets to see if there is a running joke
        val joke = targets.getOrElse(
          mark,
          Joke(target = mark, state = 0, sender = msg.user, tries = 0, jokeIdx = 0)
        )
        val jokeonme = responses.getOrElse(
          mark,
          NewJoke(
            target = mark,
            state = 0,
            sender = msg.user,
            tries = 0,
            jokeIdx = 0,
            joke = ("" -> "")
          )
        )

        // A joke has been sent or started with this user
        if (joke.state >= 1) {
          if (msg.text.matches("(?i)(no+ thanks?( you)?)")) {
            targets = targets - mark
            sender ! Response(msg, s"<@${mark}>, ok you're not in a joking mood.") // retort
          } else if (joke.tries < 3) {
            targets = targets.updated(mark, joke.copy(tries = joke.tries + 1))
            //TODO: implement retrorts
            sender ! Response(msg, s"<@${mark}> ಠ_ಠ") // retort
          } else if (joke.tries == 3) {
            targets = targets.updated(mark, joke.copy(tries = joke.tries + 1))
            sender ! Response(msg, s"""<@${mark}> you're supposed to say "who's there?".""") // retort
          } else {
            targets = targets - mark
            sender ! Response(msg, s"<@${mark}>, let's quit while we are behind. Joke ended.") // retort
          }
        } else if (jokeonme.state >= 1) {
          jokeonme.state match {
            case 0 =>
              sender ! Response(msg, s"<@${mark}>, who's there?") // retort
              responses = responses.updated(
                mark,
                jokeonme.copy(state = jokeonme.state + 1, joke = ("" -> ""))
              )
            case 1 =>
              val clue = msg.text.replaceFirst("(<@[^>]*>|v2d2|bot),? ", "")
              responses = responses.updated(
                mark,
                jokeonme.copy(state = jokeonme.state + 1, joke = (clue -> ""))
              )
              sender ! Response(msg, s"<@${mark}> ${clue} who?")
            case 2 =>
              val ans = msg.text.replaceFirst("(<@[^>]*>|v2d2|bot),? ", "")
              // pprint.log(TrackJoke(msg, KnockJoke(jokeonme.joke._1, ans)))
              // sender ! TrackJoke(msg, KnockJoke(jokeonme.joke._1, ans))
              // pprint.pprintln(s"clue: ${jokeonme.joke._1} ans: ${ans}")
              // responses = responses - mark
              // sender ! Response(msg, s"<@${mark}> Ha Ha Ha. You humans are so funny!") // retort
              responses = responses - mark
              sender ! TrackJoke(
                received = msg,
                deliver = s"""<@${mark}> Ha Ha Ha. You humans are so funny!
                             |Vote for this joke by responding with an emoji""".stripMargin,
                joke = KnockJoke(jokeonme.joke._1, ans)
              )
          }
        }
      }

    case _ =>
      None
  }
}
