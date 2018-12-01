package org.apache.spark.sql.redis.stream

import java.util.AbstractMap.SimpleEntry
import java.util.Map.Entry
import java.util.{List => JList, Map => JMap}

import com.redislabs.provider.redis.RedisConfig
import com.redislabs.provider.redis.util.ConnectionUtils.withConnection
import com.redislabs.provider.redis.util.StreamUtils.{EntryIdEarliest, createConsumerGroupIfNotExist}
import org.apache.spark.rdd.RDD
import org.apache.spark.{Partition, SparkContext, TaskContext}
import redis.clients.jedis.{EntryID, Jedis, StreamEntry}

import scala.collection.JavaConverters._

/**
  * RDD of EntryID -> StreamEntry.fields
  *
  * @author The Viet Nguyen
  */
class RedisSourceRdd(sc: SparkContext, redisConfig: RedisConfig,
                     offsetRanges: Seq[RedisSourceOffsetRange])
  extends RDD[(EntryID, JMap[String, String])](sc, Nil) {

  override def compute(split: Partition, context: TaskContext):
  Iterator[(EntryID, JMap[String, String])] = {
    val partition = split.asInstanceOf[RedisSourceRddPartition]
    val offsetRange = partition.offsetRange
    val streamKey = offsetRange.streamKey
    withConnection(redisConfig.connectionForKey(streamKey)) { conn =>
      val start = offsetRange.start.map(new EntryID(_)).getOrElse(EntryIdEarliest)
      createConsumerGroupIfNotExist(conn, streamKey, offsetRange.groupName, start)
      unreadMessages(conn, offsetRange)
    }
  }

  private def unreadMessages(conn: Jedis, offsetRange: RedisSourceOffsetRange):
  Iterator[(EntryID, JMap[String, String])] = messages(conn, offsetRange, EntryID.UNRECEIVED_ENTRY)

  private def messages(conn: Jedis, offsetRange: RedisSourceOffsetRange, start: EntryID):
  Iterator[(EntryID, JMap[String, String])] = {
    val startEntry = new SimpleEntry(offsetRange.streamKey, start)
    val min = offsetRange.start.map(new EntryID(_))
    val end = new EntryID(offsetRange.end)
    import scala.math.Ordering.Implicits._
    Stream.continually {
      conn.xreadGroup(offsetRange.groupName, "consumer-123", 1000, 100, false, startEntry)
    }
      .takeWhile { response =>
        !response.isEmpty
      }
      .flatMap {
        _.asScala.iterator
      }
      .flatMap {
        flattenRddEntry
      }
      .filter { case (entryId, _) =>
        min.isEmpty || entryId >= min.get
      }
      .takeWhile { case (entryId, _) =>
        entryId <= end
      }
      .iterator
  }

  private def flattenRddEntry(entry: Entry[String, JList[StreamEntry]]):
  Iterator[(EntryID, JMap[String, String])] = {
    entry.getValue.asScala
      .map { streamEntry =>
        streamEntry.getID -> streamEntry.getFields
      }
      .iterator
  }

  override protected def getPartitions: Array[Partition] =
    offsetRanges.zipWithIndex.map { case (e, i) => RedisSourceRddPartition(i, e) }
      .toArray
}

case class RedisSourceRddPartition(index: Int, offsetRange: RedisSourceOffsetRange)
  extends Partition
