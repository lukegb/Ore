package db.impl

import java.sql.Timestamp

import db.impl.OrePostgresDriver.api._
import db.impl.table.{DescriptionColumn, DownloadsColumn, StatTable}
import db.table.{AssociativeTable, ModelTable, NameColumn}
import models.project._
import models.statistic.{ProjectView, VersionDownload}
import models.user.role.{OrganizationRole, ProjectRole, RoleModel}
import models.user.{Notification, Organization, User}
import ore.Colors.Color
import ore.permission.role.RoleTypes.RoleType
import ore.project.Categories.Category
import ore.project.FlagReasons.FlagReason
import ore.user.Prompts.Prompt
import ore.user.notification.NotificationTypes.NotificationType

/*
 * Database schema definitions. Changes must be first applied as an evolutions
 * SQL script in "conf/evolutions/default", then here, then in the associated
 * model.
 */

trait ProjectTable extends ModelTable[Project]
  with NameColumn[Project]
  with DownloadsColumn[Project]
  with DescriptionColumn[Project] {

  def pluginId              =   column[String]("plugin_id")
  def slug                  =   column[String]("slug")
  def ownerName             =   column[String]("owner_name")
  def userId                =   column[Int]("owner_id")
  def homepage              =   column[String]("homepage")
  def recommendedVersionId  =   column[Int]("recommended_version_id")
  def category              =   column[Category]("category")
  def stars                 =   column[Int]("stars")
  def views                 =   column[Int]("views")
  def issues                =   column[String]("issues")
  def source                =   column[String]("source")
  def topicId               =   column[Int]("topic_id")
  def postId                =   column[Int]("post_id")
  def isTopicDirty          =   column[Boolean]("is_topic_dirty")
  def isVisible             =   column[Boolean]("is_visible")
  def lastUpdated           =   column[Timestamp]("last_updated")

  override def * = (id.?, createdAt.?, pluginId, ownerName, userId, homepage.?, name, slug, recommendedVersionId.?,
                    category, stars, views, downloads, issues.?, source.?, description.?, topicId, postId, isTopicDirty,
                    isVisible, lastUpdated) <> ((Project.apply _).tupled, Project.unapply)

}

class ProjectTableMain(tag: Tag) extends ModelTable[Project](tag, "projects") with ProjectTable

class ProjectTableDeleted(tag: Tag) extends ModelTable[Project](tag, "projects_deleted") with ProjectTable

class ProjectWatchersTable(tag: Tag)
  extends AssociativeTable(tag, "project_watchers", classOf[Project], classOf[User]) {

  def projectId   =   column[Int]("project_id")
  def userId      =   column[Int]("user_id")

  override def * = (projectId, userId)

}

class ProjectViewsTable(tag: Tag) extends StatTable[ProjectView](tag, "project_views", "project_id") {

  override def * = (id.?, createdAt.?, modelId, address, cookie,
                    userId.?) <> ((ProjectView.apply _).tupled, ProjectView.unapply)

}

class ProjectStarsTable(tag: Tag) extends AssociativeTable(tag, "project_stars", classOf[User], classOf[Project]) {

  def userId      =   column[Int]("user_id")
  def projectId   =   column[Int]("project_id")

  override def * = (userId, projectId)

}

class PageTable(tag: Tag) extends ModelTable[Page](tag, "pages") with NameColumn[Page] {

  def projectId     =   column[Int]("project_id")
  def slug          =   column[String]("slug")
  def contents      =   column[String]("contents")
  def isDeletable   =   column[Boolean]("is_deletable")

  override def * = (id.?, createdAt.?, projectId, name, slug, isDeletable,
                    contents) <> ((Page.apply _).tupled, Page.unapply)

}

class ChannelTable(tag: Tag) extends ModelTable[Channel](tag, "channels") with NameColumn[Channel] {

  def color       =   column[Color]("color")
  def projectId   =   column[Int]("project_id")

  override def * = (id.?, createdAt.?, projectId, name, color) <> ((Channel.apply _).tupled, Channel.unapply)
}

class VersionTable(tag: Tag) extends ModelTable[Version](tag, "versions")
  with DownloadsColumn[Version]
  with DescriptionColumn[Version] {

  def versionString   =   column[String]("version_string")
  def mcversion       =   column[String]("mcversion")
  def dependencies    =   column[List[String]]("dependencies")
  def assets          =   column[String]("assets")
  def projectId       =   column[Int]("project_id")
  def channelId       =   column[Int]("channel_id")
  def fileSize        =   column[Long]("file_size")
  def hash            =   column[String]("hash")
  def isReviewed      =   column[Boolean]("is_reviewed")
  def fileName        =   column[String]("file_name")

  override def * = (id.?, createdAt.?, projectId, versionString, mcversion.?, dependencies, assets.?, channelId,
                    fileSize, hash, description.?, downloads, isReviewed, fileName) <> ((Version.apply _).tupled,
                    Version.unapply)
}

class VersionDownloadsTable(tag: Tag) extends StatTable[VersionDownload](tag, "version_downloads", "version_id") {

  override def * = (id.?, createdAt.?, modelId, address, cookie, userId.?) <> ((VersionDownload.apply _).tupled,
                    VersionDownload.unapply)

}

class UserTable(tag: Tag) extends ModelTable[User](tag, "users") with NameColumn[User] {

  // Override to remove auto increment
  override def id   =   column[Int]("id", O.PrimaryKey)

  def fullName      =   column[String]("full_name")
  def email         =   column[String]("email")
  def tagline       =   column[String]("tagline")
  def globalRoles   =   column[List[RoleType]]("global_roles")
  def joinDate      =   column[Timestamp]("join_date")
  def avatarUrl     =   column[String]("avatar_url")
  def readPrompts   =   column[List[Prompt]]("read_prompts")

  override def * = (id.?, createdAt.?, fullName.?, name, email.?, tagline.?, globalRoles, joinDate.?,
                    avatarUrl.?, readPrompts) <> ((User.apply _).tupled, User.unapply)

}

class OrganizationTable(tag: Tag) extends ModelTable[Organization](tag, "organizations") with NameColumn[Organization] {

  override def id   =   column[Int]("id", O.PrimaryKey)
  def password      =   column[String]("password")
  def userId        =   column[Int]("user_id")

  override def * = (id.?, createdAt.?, name, password, userId) <> (Organization.tupled, Organization.unapply)

}

class OrganizationMembersTable(tag: Tag) extends AssociativeTable(tag, "organization_members", classOf[User],
  classOf[Organization]) {

  def userId          =   column[Int]("user_id")
  def organizationId  =   column[Int]("organization_id")

  override def * = (userId, organizationId)

}

trait RoleTable[R <: RoleModel] extends ModelTable[R] {

  def userId      =   column[Int]("user_id")
  def roleType    =   column[RoleType]("role_type")
  def isAccepted  =   column[Boolean]("is_accepted")

}

class OrganizationRoleTable(tag: Tag)
  extends ModelTable[OrganizationRole](tag, "user_organization_roles")
  with RoleTable[OrganizationRole] {

  def organizationId = column[Int]("organization_id")

  override def * = (id.?, createdAt.?, userId, organizationId, roleType, isAccepted) <> (OrganizationRole.tupled,
                    OrganizationRole.unapply)

}

class ProjectRoleTable(tag: Tag)
  extends ModelTable[ProjectRole](tag, "user_project_roles")
  with RoleTable[ProjectRole] {

  def projectId = column[Int]("project_id")

  override def * = (id.?, createdAt.?, userId, projectId, roleType, isAccepted) <> (ProjectRole.tupled,
                    ProjectRole.unapply)

}

class ProjectMembersTable(tag: Tag) extends AssociativeTable(tag, "project_members", classOf[Project], classOf[User]) {

  def projectId   =   column[Int]("project_id")
  def userId      =   column[Int]("user_id")

  override def * = (projectId, userId)

}

class NotificationTable(tag: Tag) extends ModelTable[Notification](tag, "notifications") {

  def userId            =   column[Int]("user_id")
  def originId          =   column[Int]("origin_id")
  def notificationType  =   column[NotificationType]("notification_type")
  def message           =   column[String]("message")
  def action            =   column[String]("action")
  def read              =   column[Boolean]("read")

  override def * = (id.?, createdAt.?, userId, originId, notificationType, message, action.?,
                    read) <> (Notification.tupled, Notification.unapply)

}

class FlagTable(tag: Tag) extends ModelTable[Flag](tag, "flags") {

  def projectId   =   column[Int]("project_id")
  def userId      =   column[Int]("user_id")
  def reason      =   column[FlagReason]("reason")
  def isResolved  =   column[Boolean]("is_resolved")

  override def * = (id.?, createdAt.?, projectId, userId, reason, isResolved) <> (Flag.tupled, Flag.unapply)

}
