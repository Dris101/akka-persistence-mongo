// Dris101 2015
package akka.persistence.mongo.serialization

import java.io.Serializable
import scala.collection.concurrent.TrieMap
import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.serialization._
import com.mongodb.casbah.Imports._
import com.mongodb.util.JSONCallback
import org.bson.{ BasicBSONEncoder, BasicBSONDecoder }

trait BsonSerializable extends Serializable {
  def toBson: MongoDBObject
}

trait BsonMaterializer {
  def fromBson(obj: MongoDBObject): BsonSerializable
}

class BsonSerializer(val system: ExtendedActorSystem) extends Serializer {
  val encoder = new BasicBSONEncoder()
  val decoder = new BasicBSONDecoder()

  val log = Logging(system, getClass.getName)
  val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(system.dynamicAccess.classLoader)
  val materializers = TrieMap.empty[Class[_], BsonMaterializer]

  override def includeManifest: Boolean = true

  // 0 - 16 reserved
  override def identifier = 479 // or whatever

  override def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case s: BsonSerializable =>
      val bson = s.toBson ++ ("type" -> obj.getClass.getTypeName)
      encoder.encode(bson)

    case _ => throw new IllegalArgumentException("Need to implement BsonSerializable trait to be able to serialize using BsonSerializer")
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) =>
        val mat = materializers.get(clazz) match {
          case Some(m) => m

          case None =>
            try {
              val classSymbol = runtimeMirror.classSymbol(clazz)
              val companionModuleSymbol = classSymbol.companion.asModule
              val moduleMirror = runtimeMirror.reflectModule(companionModuleSymbol)
              val materializer = moduleMirror.instance.asInstanceOf[BsonMaterializer]

              materializers += (clazz -> materializer)
              materializer
            } catch {
              case ex: Exception =>
                log.error(ex, "Creating materializer")
                throw new IllegalArgumentException(s"Can't materialize $clazz using BsonSerializer. No materializer found on companion object.")
            }
        }

        // Hack to get from bytes to MongoDBObject courtesy of Arthur van Hoff.
        // See https://groups.google.com/forum/#!topic/mongodb-user/CeF7h2UBgwk
        val callback = new JSONCallback()
        val n = decoder.decode(bytes, callback)
        val bsonObject = callback.get.asInstanceOf[DBObject]
        val mongoDbObject: MongoDBObject = bsonObject
        try {
          val obj = mat.fromBson(mongoDbObject)
          log.debug("Built {} from {} using {}", obj, mongoDbObject, mat)
          obj
        } catch {
          case ex: Exception =>
            log.error(ex, s"Failed to materialize $clazz from $mongoDbObject")
            throw ex
        }

      case None => throw new IllegalArgumentException("Need a manifest to use BsonSerializer")
    }
  }
}