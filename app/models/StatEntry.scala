package models

import java.sql.Timestamp
import java.util.UUID

import com.github.tminglei.slickpg.InetString
import db.model.Model
import db.model.ModelKeys._
import db.model.annotation.{Bind, BindingsGenerator}
import models.user.User
import ore.Statistics
import play.api.mvc.RequestHeader

import scala.annotation.meta.field

case class StatEntry(override val id: Option[Int] = None,
                     override val createdAt: Option[Timestamp] = None,
                     modelId: Int,
                     address: InetString,
                     cookie: String,
                     @(Bind @field) private var userId: Option[Int] = None)
                     extends Model(id, createdAt) {

  def this(modelId: Int, address: InetString, cookie: String, user: Option[User])
  = this(modelId = modelId, address = address, cookie = cookie, userId = user.flatMap(_.id))

  BindingsGenerator.generateFor(this)

  def user: Option[User] = this.userId.flatMap(User.withId)

  def user_=(user: User) = {
    this.userId = user.id
    update(UserId)
  }

  override def copyWith(id: Option[Int], theTime: Option[Timestamp]): Model = this.copy(id = id, createdAt = theTime)

}

object StatEntry {

  def bindFromRequest(modelId: Int)(implicit request: RequestHeader): StatEntry = {
    val address = InetString(request.remoteAddress)
    val cookie = request.cookies.get(Statistics.COOKIE_UID).map(_.value).getOrElse(UUID.randomUUID.toString)
    val user = User.current(request.session)
    new StatEntry(modelId, address, cookie, user)
  }

}
