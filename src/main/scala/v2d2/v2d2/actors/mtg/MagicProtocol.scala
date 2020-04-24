package v2d2.mtg

import fastparse._, SingleLineWhitespace._
import spray.json.DefaultJsonProtocol
import v2d2.actors.core.BotCombinators
import slack.models.Message

case class MagicCards()
case class CardNameSearch(
  msg: Message,
  target: String
)

object CardNameSearch extends BotCombinators {
  // v2d2, what card name is foo bar?
  // v2d2, what card name is foo bar with text?
  // v2d2, what card name is foo bar with text?
  // card name foo bar
  // card name foo bar (with text|+text|--text|-t)
  // !card name foo bar
  def word[_: P] = P(letter | P(CharIn("`',-_$\"")))
  def punc[_: P] = P(("?" | "!" | "."))
  // def is[_: P] = P(ic("is"))

  def target[_: P] = P((!punc ~ word ~ (" " | s"\t").rep).rep(3).!)

  def card[_: P] =
    P(ic("what").? ~ ic("card") ~ ic("name"))
  def text[_: P]: P[Unit] = P(ic("text"))

  // wait to implement
  // def optText[_: P]: P[Unit] =
  //   P(ic("with").? ~ ws ~ ((CharIn("+") | "--") ~ text | CharIn("-") ~ "t"))
  def opt[_: P] = P(bot.? ~ card ~/ "is ".? ~ target ~ punc.? ~ End)

  def apply(
    msg: Message
  ): Option[CardNameSearch] = {
    apply(msg.text, msg)
  }

  def apply(
    str: String,
    msg: Message
  ): Option[CardNameSearch] = {
    parse(str, opt(_)) match {
      case Parsed.Success(value, _) => // if (value.length > 2) =>
        Some(CardNameSearch(msg, value))
      case _ =>
        None
    }
  }

}
