package org.apache.spark.sql.redis.stream

import com.redislabs.provider.redis.env.RedisStandaloneEnv
import com.redislabs.provider.redis.util.ConnectionUtils.{JedisExt, XINFO, withConnection}
import com.redislabs.provider.redis.util.Person
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.redis._
import org.scalatest.{FunSuite, Matchers}
import redis.clients.jedis.{EntryID, Jedis}
import org.scalatest.concurrent.Eventually._

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationLong

/**
  * @author The Viet Nguyen
  */
class RedisStreamSourceSuite extends FunSuite with Matchers with RedisStandaloneEnv {

  test("read stream source (less than batch size)") {
    // given:
    // - I insert 10 elements to Redis XStream
    val streamKey = Person.generatePersonStreamKey()
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      (1 to 5).foreach { i =>
        conn.xadd(streamKey, new EntryID(0, i), Person.dataMaps.head.asJava)
      }
      readAndCheckData(conn, streamKey, "0-5", 5)
    }
  }

  test("read stream source (more than batch size)") {
    // given:
    // - I insert 10 elements to Redis XStream
    val streamKey = Person.generatePersonStreamKey()
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      (1 to 546).foreach { i =>
        conn.xadd(streamKey, new EntryID(0, i), Person.dataMaps.head.asJava)
      }
      readAndCheckData(conn, streamKey, "0-546", 546)
    }
  }

  test("it should continue reading from the last offset after query/spark restart") {
    val streamKey = Person.generatePersonStreamKey()
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      // write 5 items to stream
      (1 to 5).foreach { i =>
        conn.xadd(streamKey, new EntryID(0, i), Person.dataMaps.head.asJava)
      }
      readAndCheckData(conn, streamKey, "0-5", 5)

      // write 5 more items to stream
      (6 to 10).foreach { i =>
        conn.xadd(streamKey, new EntryID(0, i), Person.dataMaps.head.asJava)
      }
      readAndCheckData(conn, streamKey, "0-10", 5)
    }
  }

  test("read several streams") {
    val stream1Key = Person.generatePersonStreamKey()
    val stream2Key = Person.generatePersonStreamKey()
    withConnection(redisConfig.connectionForKey(stream1Key)) { conn =>
      (1 to 5).foreach { i =>
        conn.xadd(stream1Key, new EntryID(0, i), Person.dataMaps.head.asJava)
      }
    }
    withConnection(redisConfig.connectionForKey(stream2Key)) { conn =>
      (6 to 10).foreach { i =>
        conn.xadd(stream2Key, new EntryID(0, i), Person.dataMaps.head.asJava)
      }
    }

    val spark = readStream(s"$stream1Key,$stream2Key")
    checkLastDeliveredId(stream1Key, "0-5")
    checkLastDeliveredId(stream2Key, "0-10")
    checkCount(spark, 10)
    spark.stop()
  }

  test("read stream source (generated entry ids)") {
    val streamKey = Person.generatePersonStreamKey()
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      val autoEntryId = new EntryID() {
        override def toString: String = "*"
      }
      (1 to 1000).foreach { i =>
        conn.xadd(streamKey, autoEntryId, Person.dataMaps.head.asJava)
      }
      val spark = readStream(s"$streamKey")
      checkCount(spark, 1000)
      spark.stop()
    }
  }

    test("read stream source with concurrent write") {
      val streamKey = Person.generatePersonStreamKey()
      withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
        val autoEntryId = new EntryID() {
          override def toString: String = "*"
        }
        // write first to create stream key
        conn.xadd(streamKey, autoEntryId, Person.dataMaps.head.asJava)

        val spark = SparkSession
          .builder
          .config(conf)
          .getOrCreate()
        val persons = spark.readStream
          .format("redis")
          .schema(Person.fullSchema)
          .option(StreamOptionStreamKeys, streamKey)
          .load()
        val query = persons.writeStream
          .format("memory")
          .queryName("persons")
          .start()

        (1 to 130).foreach { i =>
          println("adding item to stream")
          conn.xadd(streamKey, autoEntryId, Person.dataMaps.head.asJava)
          Thread.sleep(10)
        }
        eventually(timeout(5 seconds)) {
          val resultDf = spark.sql("select * from persons")
          resultDf.count() shouldBe 131
        }

        spark.stop()
      }
    }


  def readAndCheckData(conn: Jedis, streamKey: String, lastDeliveredId: String, expectedRowsNum: Int): Unit = {
    val spark = readStream(streamKey)
    checkLastDeliveredId(streamKey, lastDeliveredId)
    checkCountAndLastItem(spark, lastDeliveredId, expectedRowsNum)
    spark.stop()
  }

  def readStream(streamKey: String): SparkSession = {
    val spark = SparkSession
      .builder
      .config(conf)
      .getOrCreate()
    val persons = spark.readStream
      .format("redis")
      .schema(Person.fullSchema)
      .option(StreamOptionStreamKeys, streamKey)
      .load()
    val query = persons.writeStream
      .format("memory")
      .queryName("persons")
      .start()

    println(s"query id ${query.id}")
    query.processAllAvailable()
    query.stop()
    spark
  }

  def checkLastDeliveredId(streamKey: String, lastDeliveredId: String): Unit = {
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      val groups = conn.xinfo(XINFO.SubCommandGroups, streamKey)
      groups("spark-source").asInstanceOf[Map[String, Any]](XINFO.LastDeliveredId) shouldBe lastDeliveredId
    }
  }

  def checkCount(spark: SparkSession, rowsNum: Int): Unit = {
    val resultDf = spark.sql("select * from persons").cache()
    resultDf.show()
    resultDf.count() shouldBe rowsNum
  }

  def checkCountAndLastItem(spark: SparkSession, lastDeliveredId: String, rowsNum: Int): Unit = {
    val resultDf = spark.sql("select * from persons").cache()
    resultDf.show()
    val result = resultDf.collect()
    result.length should be(rowsNum)
    result.map(row => row.getAs[String]("_id")).last shouldBe lastDeliveredId
  }

}
