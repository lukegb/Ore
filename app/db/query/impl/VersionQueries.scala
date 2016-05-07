package db.query.impl

import db.dao.ModelFilter
import db.driver.OrePostgresDriver.api._
import db.query.ModelQueries
import db.query.ModelQueries.run
import db.{VersionDownloadsTable, VersionTable}
import models.project.Version

import scala.concurrent.Future

/**
  * Version related queries.
  */
class VersionQueries extends ModelQueries[VersionTable, Version](classOf[Version], TableQuery[VersionTable]) {

  val Downloads = ModelQueries.registrar.register(
    new StatQueries[VersionDownloadsTable](TableQuery[VersionDownloadsTable])
  )

  private val downloads = TableQuery[VersionDownloadsTable]

  /**
    * Returns true if the specified hash is found in the specified
    * [[models.project.Project]]'s [[Version]]s.
    *
    * @param projectId  Project ID
    * @param hash       Version hash
    * @return           True if found
    */
  def hashExists(projectId: Int, hash: String): Future[Boolean] = run(((for {
      model <- this.baseQuery
      if model.projectId === projectId
      if model.hash === hash
  } yield model.id).length > 0).result)

  /**
    * Returns a filter based on a Version's channel.
    *
    * @param channelIds Channel IDs to filter versions with
    * @return           Channel filter
    */
  def channelFilter(channelIds: Seq[Int]): ModelFilter[VersionTable, Version]
  = ModelFilter(_.channelId inSetBind channelIds)

}
