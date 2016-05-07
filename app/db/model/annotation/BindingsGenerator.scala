package db.model.annotation

import db.driver.OrePostgresDriver.api._
import db.model.{Model, ModelTable}
import util.Conf.debug

import scala.reflect.runtime.universe._

/**
  * Processes an annotated Model and bind's the appropriate fields and
  * children.
  */
object BindingsGenerator {

  /**
    * Generates bindings for the specified model.
    *
    * @param model  Model to bind
    * @tparam T     Model table
    * @tparam M     Model type
    */
  def generateFor[T <: ModelTable[M], M <: Model: TypeTag](model: M) = {
    debug("Generating bindings for model " + model)
    generateFieldsFor(model)
    generateRelationsFor(model)
  }

  def generateFieldsFor[T <: ModelTable[M], M <: Model: TypeTag](model: M) = {
    debug("Generating field for model " + model)
    // Bind marked fields
    val modelClass = model.getClass
    //noinspection ComparingUnrelatedTypes
    val bindFields = modelClass.getDeclaredFields
      .filter(_.getDeclaredAnnotations.exists(_.annotationType.equals(classOf[Bind])))
    bindFields.foreach(f => debug(f.toString))

    for (bindField <- bindFields) {
      val bindData = bindField.getAnnotation(classOf[Bind])
      val fieldName = bindField.getName.substring(bindField.getName.lastIndexOf("$") + 1)
      var fieldType = bindField.getType

      // Default to field name for key
      var key = bindData.value
      if (key.isEmpty) key = fieldName
      if (key.startsWith("_")) key = key.substring(1)

      if (fieldType.equals(classOf[Option[_]])) {
        // Lift type out of option
        fieldType = runtimeMirror(getClass.getClassLoader)
          .runtimeClass(typeTag[M].tpe.members
            .filterNot(_.isMethod)
            .filter(m => m.name.decodedName.toString.trim.equals(fieldName))
            .map(_.typeSignature.typeArgs.head).head.typeSymbol.asClass)
      }

      TypeSetters.get(fieldType)
        .getOrElse(throw new RuntimeException("No type setter found for type: " + fieldType))
        .bindTo(model, key, bindField)
    }
  }

  def generateRelationsFor[T <: ModelTable[M], M <: Model](model: M) = {
    debug("Generating relations for model " + model)
    val modelClass = model.getClass
    val key = modelClass.getSimpleName.toLowerCase + "Id"
    //noinspection ComparingUnrelatedTypes
    if (modelClass.getDeclaredAnnotations.exists(_.annotationType.equals(classOf[HasMany]))) {
      val relations = modelClass.getDeclaredAnnotation(classOf[HasMany])
      for (relation <- relations.value) model.bindMany(relation, t => getRep[Int](key, t))
    }
  }

  def getRep[A](name: String, table: ModelTable[_])
  = table.getClass.getMethod(name).invoke(table).asInstanceOf[Rep[A]]

}
