package controllers.project

import java.nio.file.{Files, StandardCopyOption}
import javax.inject.Inject

import controllers.BaseController
import controllers.Requests.ProjectRequest
import controllers.project.routes.{Versions => self}
import db.ModelService
import db.impl.OrePostgresDriver.api._
import discourse.impl.OreDiscourseApi
import form.OreForms
import models.project.{Channel, Project, Version}
import ore.permission.{EditVersions, ReviewProjects}
import ore.project.factory.{PendingProject, ProjectFactory}
import ore.project.io.{InvalidPluginFileException, PluginFile}
import ore.{OreConfig, OreEnv, StatTracker}
import play.api.i18n.MessagesApi
import play.api.mvc.Result
import util.StringUtils.equalsIgnoreCase
import views.html.projects.{versions => views}

/**
  * Controller for handling Version related actions.
  */
class Versions @Inject()(stats: StatTracker,
                         forms: OreForms,
                         factory: ProjectFactory,
                         forums: OreDiscourseApi,
                         implicit override val messagesApi: MessagesApi,
                         implicit override val env: OreEnv,
                         implicit override val config: OreConfig,
                         implicit override val service: ModelService)
                         extends BaseController {

  private val fileManager = this.projects.fileManager

  private def VersionEditAction(author: String, slug: String)
  = AuthedProjectAction(author, slug) andThen ProjectPermissionAction(EditVersions)

  /**
    * Shows the specified version view page.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return Version view
    */
  def show(author: String, slug: String, versionString: String) = {
    ProjectAction(author, slug) { implicit request =>
      implicit val project = request.project
      withVersion(versionString) { version =>
        this.stats.projectViewed { implicit request =>
          Ok(views.view(project, version.channel, version))
        }
      }
    }
  }

  /**
    * Saves the specified Version's description.
    *
    * @param author        Project owner
    * @param slug          Project slug
    * @param versionString Version name
    * @return View of Version
    */
  def saveDescription(author: String, slug: String, versionString: String) = {
    VersionEditAction(author, slug) { implicit request =>
      implicit val project = request.project
      withVersion(versionString) { version =>
        version.description = this.forms.VersionDescription.bindFromRequest.get.trim
        Redirect(self.show(author, slug, versionString))
      }
    }
  }

  /**
    * Sets the specified Version as the recommended download.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version name
    * @return               View of version
    */
  def setRecommended(author: String, slug: String, versionString: String) = {
    VersionEditAction(author, slug) { implicit request =>
      implicit val project = request.project
      withVersion(versionString) { version =>
        project.recommendedVersion = version
        Redirect(self.show(author, slug, versionString))
      }
    }
  }

  /**
    * Sets the specified Version as approved by the moderation staff.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version name
    * @return               View of version
    */
  def approve(author: String, slug: String, versionString: String) = {
    (AuthedProjectAction(author, slug) andThen ProjectPermissionAction(ReviewProjects)) { implicit request =>
      implicit val project = request.project
      withVersion(versionString) { version =>
        version.setReviewed(reviewed = true)
        Redirect(self.show(author, slug, versionString))
      }
    }
  }

  /**
    * Displays the "versions" tab within a Project view.
    *
    * @param author   Owner of project
    * @param slug     Project slug
    * @param channels Visible channels
    * @return View of project
    */
  def showList(author: String, slug: String, channels: Option[String]) = {
    ProjectAction(author, slug) { implicit request =>
      val project = request.project
      val allChannels = project.channels.toSeq

      var visibleNames: Option[Array[String]] = channels.map(_.toLowerCase.split(','))
      val visible: Option[Array[Channel]] = visibleNames.map(_.map { name =>
        allChannels.find(_.name.equalsIgnoreCase(name)).get
      })
      val visibleIds: Array[Int] = visible.map(_.map(_.id.get)).getOrElse(allChannels.map(_.id.get).toArray)

      val load = this.config.projects.getInt("init-version-load").get
      val versions = project.versions.sorted(_.createdAt.desc, _.channelId inSetBind visibleIds, load)
      if (visibleNames.isDefined && visibleNames.get.toSet.equals(allChannels.map(_.name.toLowerCase).toSet)) {
        visibleNames = None
      }

      this.stats.projectViewed { implicit request =>
        Ok(views.list(project, allChannels, versions, visibleNames))
      }
    }
  }

  /**
    * Shows the creation form for new versions on projects.
    *
    * @param author Owner of project
    * @param slug   Project slug
    * @return Version creation view
    */
  def showCreator(author: String, slug: String) = {
    VersionEditAction(author, slug) { implicit request =>
      val project = request.project
      Ok(views.create(project, None, Some(project.channels.all.toSeq), showFileControls = true))
    }
  }

  /**
    * Uploads a new version for a project for further processing.
    *
    * @param author Owner name
    * @param slug   Project slug
    * @return Version create page (with meta)
    */
  def upload(author: String, slug: String) = {
    VersionEditAction(author, slug) { implicit request =>
      request.body.asMultipartFormData.get.file("pluginFile") match {
        case None =>
          Redirect(self.showCreator(author, slug)).flashing("error" -> "Missing file")
        case Some(tmpFile) =>
          // Process the uploaded file
          var plugin: PluginFile = null
          try {
            plugin = this.factory.processPluginFile(tmpFile.ref, tmpFile.filename, request.user)
          } catch {
            case e: InvalidPluginFileException =>
              Redirect(self.showCreator(author, slug)).flashing("error" -> "Invalid plugin file.")
          }

          // Validate
          val project = request.project
          if (!plugin.meta.get.getId.equals(project.pluginId))
            Redirect(self.showCreator(author, slug))
              .flashing("error" -> "The uploaded plugin ID must match your project's plugin ID.")
          else {
            val pendingVersion = this.factory.startVersion(plugin, project, project.channels.all.head.name)
            if (pendingVersion.underlying.exists && this.config.projects.getBoolean("file-validate").get)
              Redirect(self.showCreator(author, slug))
                .flashing("error" -> "Found a duplicate file in project. Plugin files may only be uploaded once.")
            else {
              // Cache and redirect
              pendingVersion.cache()
              Redirect(self.showCreatorWithMeta(author, slug, pendingVersion.underlying.versionString))
            }
          }
      }
    }
  }

  /**
    * Displays the "version create" page with the associated plugin meta-data.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return Version create view
    */
  def showCreatorWithMeta(author: String, slug: String, versionString: String) = {
    Authenticated { implicit request =>
      // Get pending version
      val username = request.user.name
      this.factory.getPendingVersion(username, slug, versionString) match {
        case None =>
          Redirect(self.showCreator(author, slug))
        case Some(pendingVersion) =>
          // Get project
          pendingOrReal(username, slug) match {
            case None =>
              Redirect(self.showCreator(author, slug))
            case Some(p) => p match {
              case pending: PendingProject =>
                Ok(views.create(pending.underlying, Some(pendingVersion), None, showFileControls = false))
              case real: Project =>
                Ok(views.create(real, Some(pendingVersion), Some(real.channels.toSeq), showFileControls = true))
            }
          }
      }
    }
  }

  private def pendingOrReal(author: String, slug: String): Option[Any] = {
    // Returns either a PendingProject or existing Project
    projects.withSlug(author, slug) match {
      case None => this.factory.getPendingProject(author, slug)
      case Some(project) => Some(project)
    }
  }

  /**
    * Completes the creation of the specified pending version or project if
    * first version.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return New version view
    */
  def publish(author: String, slug: String, versionString: String) = {
    Authenticated { implicit request =>
      // First get the pending Version
      val username = request.user.name
      this.factory.getPendingVersion(username, slug, versionString) match {
        case None =>
          // Not found
          Redirect(self.showCreator(author, slug))
        case Some(pendingVersion) =>
          // Get submitted channel
          this.forms.VersionCreate.bindFromRequest.fold(
            hasErrors => {
              // Invalid channel
              Redirect(self.showCreatorWithMeta(author, slug, versionString))
                .flashing("error" -> hasErrors.errors.head.message)
            },

            versionData => {
              // Channel is valid
              pendingVersion.channelName = versionData.channelName.trim
              pendingVersion.channelColor = versionData.color

              // Check for pending project
              this.factory.getPendingProject(username, slug) match {
                case None =>
                  // No pending project, create version for existing project
                  withProject(author, slug) { project =>
                    val existingChannel = project.channels.find {
                      equalsIgnoreCase(_.name, pendingVersion.channelName)
                    }.orNull

                    var channelResult: Either[String, Channel] = Right(existingChannel)
                    if (existingChannel == null)
                      channelResult = versionData.addTo(project)

                    channelResult.fold(
                      error => {
                        Redirect(self.showCreatorWithMeta(author, slug, versionString))
                          .flashing("error" -> error)
                      },
                      channel => {
                        val newVersion = pendingVersion.complete.get
                        if (versionData.recommended)
                          project.recommendedVersion = newVersion

                        // Create forum topic reply
                        this.forums.postVersionRelease(project, newVersion)

                        Redirect(self.show(author, slug, versionString))
                      }
                    )
                  }
                case Some(pendingProject) =>
                  // Found a pending project, create it with first version
                  pendingProject.complete.get
                  Redirect(routes.Projects.show(author, slug))
              }
            }
          )
      }
    }
  }

  /**
    * Deletes the specified version and returns to the version page.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return Versions page
    */
  def delete(author: String, slug: String, versionString: String) = {
    VersionEditAction(author, slug) { implicit request =>
      implicit val project = request.project
      withVersion(versionString) { version =>
        this.projects.deleteVersion(version)
        Redirect(self.showList(author, slug, None))
      }
    }
  }

  /**
    * Sends the specified Project Version to the client.
    *
    * @param author        Project owner
    * @param slug          Project slug
    * @param versionString Version string
    * @return Sent file
    */
  def download(author: String, slug: String, versionString: String) = {
    ProjectAction(author, slug) { implicit request =>
      implicit val project = request.project
      withVersion(versionString) { version =>
        this.stats.versionDownloaded(version) { implicit request =>
          Ok.sendFile(this.fileManager.getProjectDir(author, project.name).resolve(version.fileName).toFile)
        }
      }
    }
  }

  /**
    * Sends the specified project's current recommended version to the client.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Sent file
    */
  def downloadRecommended(author: String, slug: String) = {
    ProjectAction(author, slug) { implicit request =>
      val project = request.project
      val rv = project.recommendedVersion
      this.stats.versionDownloaded(rv) { implicit request =>
        Ok.sendFile(this.fileManager.getProjectDir(author, project.name).resolve(rv.fileName).toFile)
      }
    }
  }

  /**
    * Downloads the specified version as a JAR regardless of the original
    * uploaded file type.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version name
    * @return               Sent file
    */
  def downloadJar(author: String, slug: String, versionString: String) = {
    ProjectAction(author, slug) { implicit request =>
      implicit val project = request.project
      withVersion(versionString)(version => sendJar(project, version))
    }
  }

  /**
    * Downloads the specified version as a JAR regardless of the original
    * uploaded file type.
    *
    * @param pluginId       Project unique plugin ID
    * @param versionString  Version name
    * @return               Sent file
    */
  def downloadJarById(pluginId: String, versionString: String) = {
    ProjectAction(pluginId) { implicit request =>
      implicit val project = request.project
      withVersion(versionString)(version => sendJar(project, version))
    }
  }

  /**
    * Downloads the Project's recommended version as a JAR regardless of the
    * original uploaded file type.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Sent file
    */
  def downloadRecommendedJar(author: String, slug: String) = {
    ProjectAction(author, slug) { implicit request =>
      val project = request.project
      sendJar(project, project.recommendedVersion)
    }
  }

  /**
    * Downloads the Project's recommended version as a JAR regardless of the
    * original uploaded file type.
    *
    * @param pluginId Project unique plugin ID
    * @return         Sent file
    */
  def downloadRecommendedJarById(pluginId: String) = {
    ProjectAction(pluginId) { implicit request =>
      val project = request.project
      sendJar(project, project.recommendedVersion)
    }
  }

  private def sendJar(project: Project, version: Version)(implicit request: ProjectRequest[_]): Result = {
    val fileName = version.fileName
    val path = this.fileManager.getProjectDir(project.ownerName, project.name).resolve(fileName)
    this.stats.versionDownloaded(version) { implicit request =>
      if (fileName.endsWith(".jar"))
        Ok.sendFile(path.toFile)
      else {
        val pluginFile = new PluginFile(path, project.owner.user)
        val jarName = fileName.substring(0, fileName.lastIndexOf('.')) + ".jar"
        val jarPath = this.fileManager.env.tmp.resolve(project.ownerName).resolve(jarName)
        val jarIn = pluginFile.newJarStream
        Files.copy(jarIn, jarPath, StandardCopyOption.REPLACE_EXISTING)
        jarIn.close()
        Ok.sendFile(jarPath.toFile, onClose = () => Files.delete(jarPath))
      }
    }
  }

}
