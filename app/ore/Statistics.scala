package ore

import controllers.Requests.ProjectRequest
import db.model.Models
import models.StatEntry
import models.project.Version
import play.api.mvc.{Cookie, Result}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Helper class for handling tracking of statistics.
  */
object Statistics {

  val COOKIE_UID = "uid"

  /**
    * Signifies that a project has been viewed with the specified request and
    * actions should be taken to check whether a view should be added to the
    * Project's view count.
    *
    * @param request Request to view the project
    */
  def projectViewed(f: ProjectRequest[_] => Result)(implicit request: ProjectRequest[_]): Result = {
    val project = request.project
    val statEntry = StatEntry.bindFromRequest(project.id.get)
    Models.Projects.Views.record(statEntry).andThen {
      case recorded => if (recorded.get) project.addView()
    }
    f(request).withCookies(Cookie(COOKIE_UID, statEntry.cookie))
  }

  /**
    * Signifies that a version has been downloaded with the specified request
    * and actions should be taken to check whether a view should be added to
    * the Version's (and Project's) download count.
    *
    * @param version Version to check downloads for
    * @param request Request to download the version
    */
  def versionDownloaded(version: Version)(f: ProjectRequest[_] => Result)(implicit request: ProjectRequest[_]): Result = {
    val statEntry = StatEntry.bindFromRequest(version.id.get)
    Models.Versions.Downloads.record(statEntry).andThen {
      case recorded => if (recorded.get) {
        version.addDownload()
        request.project.addDownload()
      }
    }
    f(request).withCookies(Cookie(COOKIE_UID, statEntry.cookie))
  }

}
