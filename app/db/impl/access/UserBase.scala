package db.impl.access

import java.sql.Timestamp

import db.impl.schema.ProjectSchema
import db.{ModelBase, ModelService}
import discourse.impl.OreDiscourseApi
import models.user.User
import ore.OreConfig
import play.api.mvc.Session
import util.StringUtils._

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Represents a central location for all Users.
  */
class UserBase(override val service: ModelService,
               forums: OreDiscourseApi,
               config: OreConfig)
  extends ModelBase[User] {

  import UserBase._

  override val modelClass = classOf[User]

  /**
    * Returns the user with the specified username. If the specified username
    * is not found in the database, an attempt will be made to fetch the user
    * from Discourse.
    *
    * @param username Username of user
    * @return User if found, None otherwise
    */
  def withName(username: String): Option[User] = {
    this.find(equalsIgnoreCase(_.name, username)).orElse {
      // Try to get user from forums, or return None if can't connect
      this.forums.await(this.forums.fetchUser(username).recover {
        case e: Exception => None // ignore
      }).map(u => getOrCreate(User.fromDiscourse(u)))
    }
  }

  /**
    * Returns a page of [[User]]s with at least one [[models.project.Project]].
    *
    * FIXME: Ordering is messed up
    *
    * @return Users with at least one project
    */
  def getAuthors(ordering: String = ORDERING_PROJECTS, page: Int = 1): Seq[User] = {
    // determine ordering
    var sort = ordering
    val reverse = if (sort.startsWith("-")) {
      sort = sort.substring(1)
      false
    } else true

    // get authors
    var users: Seq[User] = this.service.await {
      this.service.getSchema(classOf[ProjectSchema]).distinctAuthors
    }.get

    // sort
    sort match {
      case ORDERING_PROJECTS => users = users.sortBy(u => (u.projects.size, u.username))
      case ORDERING_JOIN_DATE => users = users.sortBy(u => (u.joinDate.getOrElse(u.createdAt.get), u.username))
      case ORDERING_USERNAME => users = users.sortBy(_.username)
      case _ => users.sortBy(u => (u.projects.size, u.username))
    }

    // get page slice
    val pageSize = this.config.users.getInt("author-page-size").get
    val offset = (page - 1) * pageSize
    users = users.slice(offset, offset + pageSize)
    if (reverse) users.reverse else users
  }

  implicit val timestampOrdering: Ordering[Timestamp] = new Ordering[Timestamp] {
    def compare(x: Timestamp, y: Timestamp) = x compareTo y
  }

  /**
    * Attempts to find the specified User in the database or creates a new User
    * if one does not exist.
    *
    * @param user User to find
    * @return     Found or new User
    */
  def getOrCreate(user: User): User = this.service.await(user.schema(this.service).getOrInsert(user)).get

  /**
    * Returns the currently authenticated User.
    *
    * @param session  Current session
    * @return         Authenticated user, if any, None otherwise
    */
  def current(implicit session: Session): Option[User] = session.get("username").map(withName).getOrElse(None)

}

object UserBase {

  val ORDERING_PROJECTS = "projects"
  val ORDERING_USERNAME = "username"
  val ORDERING_JOIN_DATE = "joined"

}
