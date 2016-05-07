package db.model

import java.sql.Timestamp

import db.driver.OrePostgresDriver.api._
import slick.lifted.Tag

/** Represents a Table in the database that contains Models. */
abstract class ModelTable[M <: Model](tag: Tag, name: String) extends Table[M](tag, name) {
  /** The Model's primary key column */
  def id        = column[Int]("id", O.PrimaryKey, O.AutoInc)
  /** The [[java.sql.Timestamp]] instant of when a Model was created. */
  def createdAt = column[Timestamp]("created_at")
}
