import scala.concurrent.duration._
import akka.actor._
import akka.persistence._
import akka.testkit._
import akka.serialization._
import com.typesafe.config.ConfigFactory

import akka.persistence.mongo._
import akka.persistence.mongo.serialization._

import com.mongodb.casbah.Imports._

import org.scalatest._

object SerializationSpec {
  def config(port: Int) = ConfigFactory.parseString(
    s"""
      |akka.actor.serialize-messages = on
      |akka.actor.serializers {
      |  drissoft = "akka.persistence.mongo.serialization.BsonSerializer"
      |}
      |akka.actor.serialization-bindings {
      |  "akka.persistence.mongo.serialization.BsonSerializable" = drissoft
      |}
      |akka.persistence.journal.plugin = "casbah-journal"
      |akka.persistence.snapshot-store.plugin = "casbah-snapshot-store"
      |akka.persistence.journal.max-deletion-batch-size = 3
      |akka.persistence.publish-plugin-commands = on
      |akka.persistence.publish-confirmations = on
      |casbah-journal.mongo-journal-url = "mongodb://localhost:$port/store.messages"
      |casbah-journal.mongo-journal-write-concern = "acknowledged"
      |casbah-journal.mongo-journal-write-concern-timeout = 10000
      |casbah-snapshot-store.mongo-snapshot-url = "mongodb://localhost:$port/store.snapshots"
      |casbah-snapshot-store.mongo-snapshot-write-concern = "acknowledged"
      |casbah-snapshot-store.mongo-snapshot-write-concern-timeout = 10000
     """.stripMargin)

  trait Event extends BsonPersistable with BsonSerializable

  object SerializableEvent extends BsonMaterializer {
    override def fromBson(obj: MongoDBObject) = SerializableEvent(obj.as[String]("name"), obj.as[Double]("age"))
  }

  case class SerializableEvent(name: String, age: Double) extends Event {
    override def toBson = MongoDBObject("name" -> name, "age" -> age)
  }

  class PersistentActorTest(val persistenceId: String, testEvent: Event, replyTo: ActorRef) extends PersistentActor with ActorLogging {
    def receiveRecover: Receive = handle
    def receiveCommand: Receive = {
      case "TestMe" => persist(testEvent){ replyTo ! "Persisted "+ _.toString }
    }

    def handle: Receive = {
      case payload: Event =>
        replyTo ! payload
        
       case _ =>
    }
  }
}

import SerializationSpec._

class SerializationSpec extends TestKit(ActorSystem("test", config(27017)))
  with ImplicitSender
  with WordSpecLike
  with Matchers {

  val serialization = SerializationExtension(system)
  val event = SerializableEvent("Joe Bloggs", 94)
  
  "BsonSerialzer" must {
    "round trip a class" in {
      
      val serializer = serialization.findSerializerFor(event)
      val bytes = serializer.toBinary(event)
      val rehydrated = serializer.fromBinary(bytes, event.getClass).asInstanceOf[BsonSerializable]
      info(s"In: $event. Out: $rehydrated")
      assert(event == rehydrated)
    }
  }

  "The plug-in" must {
    "persist event as BSON and recover using it" in {
      val origActorRef = system.actorOf(Props(classOf[PersistentActorTest], "Tester1", event, testActor))
      origActorRef ! "TestMe"
      expectMsg(1.second, s"Persisted $event")
      val recoveredActorRef = system.actorOf(Props(classOf[PersistentActorTest], "Tester1", null, testActor))
      expectMsg(1.seconds, event)
    }
  }
}
