package db.query

import java.sql.Timestamp
import java.util.Date
import java.util.concurrent.TimeUnit

import db.dao.ModelFilter
import db.driver.OrePostgresDriver.api._
import db.model.{Model, ModelRegistrar, ModelTable, Models}
import play.api.Play
import play.api.db.slick.DatabaseConfigProvider
import slick.dbio.{DBIOAction, NoStream}
import slick.driver.JdbcProfile
import slick.lifted.ColumnOrdered
import util.Conf._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * Base class for handling Model queries.
  */
class ModelQueries[Table <: ModelTable[Row], Row <: Model](val modelClass: Class[Row],
                                                           val baseQuery: TableQuery[Table]) {

  type Filter = Table => Rep[Boolean]

  // Generic (delegate to companion object)

  def insert(model: Row) = ModelQueries.insert(model)
  def find(predicate: Table => Rep[Boolean]) = ModelQueries.find(modelClass, predicate)
  def count(filter: Table => Rep[Boolean] = null) = ModelQueries.count(modelClass, filter)
  def delete(model: Row) = ModelQueries.delete(model)
  def deleteWhere(filter: Table => Rep[Boolean]) = ModelQueries.deleteWhere(modelClass, filter)
  def get(id: Int, filter: Table => Rep[Boolean]) = ModelQueries.get(modelClass, id, filter)
  def collect(limit: Int = -1, offset: Int = -1, filter: Table => Rep[Boolean] = null,
              sort: Table => ColumnOrdered[_] = null)
  = ModelQueries.collect(modelClass, filter, sort, limit, offset)
  def filter(filter: Table => Rep[Boolean], limit: Int = -1, offset: Int = -1)
  = ModelQueries.filter(modelClass, filter, limit, offset)

  // Model specific

  /**
    * Returns the specified model or creates it if it doesn't exist.
    *
    * @param model  Model to get or create
    * @return       Existing or newly created model
    */
  def getOrInsert(model: Row): Future[Row] = {
    val modelPromise = Promise[Row]
    like(model).onComplete {
      case Failure(thrown) => modelPromise.failure(thrown)
      case Success(modelOpt) => modelOpt match {
        case Some(existing) => modelPromise.success(existing)
        case None => modelPromise.completeWith(ModelQueries insert model)
      }
    }
    modelPromise.future
  }

  /**
    * Tries to find the specified model in it's table with an unset ID.
    *
    * @param model  Model to find
    * @return       Model if found
    */
  def like(model: Row): Future[Option[Row]] = Future(None)

}

/**
  * Contains all queries for retrieving models from the database.
  */
object ModelQueries extends Setters {

  implicit def unwrapFilter[T <: ModelTable[M], M <: Model](filter: ModelFilter[T, M]): T => Rep[Boolean]
  = if (filter == null) null else filter.fn
  implicit def wrapFilter[T <: ModelTable[M], M <: Model](fn: T => Rep[Boolean]): ModelFilter[T, M]
  = ModelFilter(fn)

  /** Filters models by ID */
  def IdFilter[T <: ModelTable[M], M <: Model](id: Int): ModelFilter[T, M] = ModelFilter(_.id === id)

  val registrar = new ModelRegistrar
  import registrar.modelQuery

  Models // Initialize models

  protected[db] val Config = DatabaseConfigProvider.get[JdbcProfile](Play.current)
  protected val DB = Config.db
  protected[db] def theTime: Timestamp = new Timestamp(new Date().getTime)

  // Utilities

  /**
    * Runs the specified action on the DB.
    *
    * @param a  Action to run
    * @return   Result
    */
  def run[R](a: DBIOAction[R, NoStream, Nothing]): Future[R] = DB.run(a)

  /**
    * The default timeout when awaiting a query result.
    */
  val DefaultTimeout: Duration = Duration(AppConf.getInt("db.default-timeout").get, TimeUnit.SECONDS)

  /**
    * Awaits the result of the specified future and returns the result.
    *
    * @param f        Future to await
    * @param timeout  Timeout duration
    * @tparam M       Return type
    * @return         Try of return type
    */
  def await[M](f: Future[M], timeout: Duration = DefaultTimeout): Try[M] = Await.ready(f, timeout).value.get

  /**
    * Creates the specified model in it's table.
    *
    * @param model  Model to create
    * @return       Newly created model
    */
  def insert[T <: ModelTable[M], M <: Model](model: M): Future[M] = {
    val toInsert = model.copyWith(None, Some(theTime)).asInstanceOf[M]
    val models = modelQuery[T, M](model.getClass)
    run {
      models returning models.map(_.id) into {
        case (m, id) =>
          model.copyWith(Some(id), m.createdAt).asInstanceOf[M]
      } += toInsert
    }
  }

  /**
    * Returns the first model that matches the given predicate.
    *
    * @param predicate  Predicate
    * @return           Optional result
    */
  def find[T <: ModelTable[M], M <: Model](modelClass: Class[_ <: M],
                                           predicate: T => Rep[Boolean]): Future[Option[M]] = {
    val modelPromise = Promise[Option[M]]
    val query = modelQuery[T, M](modelClass).filter(predicate).take(1)
    run(query.result).andThen {
      case Failure(thrown) => modelPromise.failure(thrown)
      case Success(result) => modelPromise.success(result.headOption)
    }
    modelPromise.future
  }

  /**
    * Returns the size of the model table.
    *
    * @return Size of model table
    */
  def count[T <: ModelTable[M], M <: Model](modelClass: Class[_ <: M],
                                            filter: T => Rep[Boolean] = null): Future[Int] = {
    var query = modelQuery[T, M](modelClass)
    if (filter != null) query = query.filter(filter)
    run(query.length.result)
  }

  /**
    * Deletes the specified Model.
    *
    * @param model Model to delete
    */
  def delete[T <: ModelTable[M], M <: Model](model: M, filter: T => Rep[Boolean] = null): Future[Int]
  = run(modelQuery[T, M](model.getClass).filter(IdFilter[T, M](model.id.get) && filter).delete)

  /**
    * Deletes all the models meeting the specified filter.
    *
    * @param modelClass Model class
    * @param filter     Filter to use
    * @tparam T         Table
    * @tparam M         Model
    */
  def deleteWhere[T <: ModelTable[M], M <: Model](modelClass: Class[_ <: M], filter: T => Rep[Boolean]): Future[Int]
  = run(modelQuery[T, M](modelClass).filter(filter).delete)

  /**
    * Returns the model with the specified ID, if any.
    *
    * @param id   Model with ID
    * @return     Model if present, None otherwise
    */
  def get[T <: ModelTable[M], M <: Model](modelClass: Class[_ <: M], id: Int,
                                          filter: T => Rep[Boolean] = null): Future[Option[M]]
  = find[T, M](modelClass, IdFilter[T, M](id) && filter)

  /**
    * Returns a collection of models with the specified limit and offset.
    *
    * @param limit  Amount of models to take
    * @param offset Offset to drop
    * @return       Collection of models
    */
  def collect[T <: ModelTable[M], M <: Model](modelClass: Class[_ <: M], filter: T => Rep[Boolean] = null,
                                              sort: T => ColumnOrdered[_] = null,
                                              limit: Int = -1, offset: Int = -1): Future[Seq[M]] = {
    var query = modelQuery[T, M](modelClass)
    if (filter != null) query = query.filter(filter)
    if (sort != null) query = query.sortBy(sort)
    if (offset > -1) query = query.drop(offset)
    if (limit > -1) query = query.take(limit)
    run(query.result)
  }

  /**
    * Filters the the models.
    *
    * @param filter Model filter
    * @param limit  Amount to take
    * @param offset Amount to drop
    * @tparam T     Model table
    * @tparam M     Model
    * @return       Filtered models
    */
  def filter[T <: ModelTable[M], M <: Model](modelClass: Class[_ <: M], filter: T => Rep[Boolean],
                                             limit: Int = -1, offset: Int = -1): Future[Seq[M]]
  = collect(modelClass, filter = filter, limit = limit, offset = offset)

}
