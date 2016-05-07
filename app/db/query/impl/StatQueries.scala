package db.query.impl

import db.dao.ModelFilter
import db.driver.OrePostgresDriver.api._
import db.model.StatTable
import db.query.ModelQueries
import db.query.ModelQueries.unwrapFilter
import models.StatEntry

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

class StatQueries[T <: StatTable](baseQuery: TableQuery[T]) extends ModelQueries(classOf[StatEntry], baseQuery) {

  /**
    * Checks if the specified StatEntry exists and records the entry in the
    * database by either inserting a new entry or updating an existing entry
    * with the User ID if applicable. Returns true if recorded in database.
    *
    * @param entry  Entry to check
    * @return       True if recorded in database
    */
  def record(entry: StatEntry): Future[Boolean] = {
    val promise = Promise[Boolean]
    this.like(entry).andThen {
      case result => result.get match {
        case None =>
          // No previous entry found, insert new entry
          promise.completeWith(insert(entry).map(_.isDefined))
        case Some(existingEntry) =>
          // Existing entry found, update the User ID if possible
          if (existingEntry.user.isEmpty && entry.user.isDefined) {
            existingEntry.user = entry.user.get
          }
          promise.success(false)
      }
    }
    promise.future
  }

  override def like(entry: StatEntry): Future[Option[StatEntry]] = {
    val baseFilter: ModelFilter[T, StatEntry] = ModelFilter(_.modelId === entry.modelId)
    val filter: Filter = e => e.address === entry.address || e.cookie === entry.cookie
    val userFilter = entry.user.map[Filter](u => e => filter(e) || e.userId === u.id.get).getOrElse(filter)
    this.find(baseFilter && userFilter)
  }

}
