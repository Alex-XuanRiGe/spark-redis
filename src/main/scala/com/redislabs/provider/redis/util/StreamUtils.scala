package com.redislabs.provider.redis.util

import org.apache.commons.lang3.StringUtils
import redis.clients.jedis.{EntryID, Jedis}

/**
  * @author The Viet Nguyen
  */
object StreamUtils extends Logging {

  val EntryIdEarliest = new EntryID(0, 0)

  def createConsumerGroupIfNotExist(conn: Jedis, streamKey: String, groupName: String,
                                    offset: EntryID): Unit = {
    createConsumerGroupIfNotExistOrElse(conn, streamKey, groupName, offset) {
      // ignore existing group
    }
  }

  def createConsumerGroupIfNotExistOrElseReset(conn: Jedis, streamKey: String, groupName: String,
                                               offset: EntryID): Unit = {
    createConsumerGroupIfNotExistOrElse(conn, streamKey, groupName, offset) {
      conn.xgroupSetID(streamKey, groupName, offset)
    }
  }

  private def createConsumerGroupIfNotExistOrElse(conn: Jedis, streamKey: String, groupName: String,
                                                  offset: EntryID)(orElse: => Unit): Unit = {
    try {
      conn.xgroupCreate(streamKey, groupName, offset, true)
    } catch {
      case e: Exception if StringUtils.contains(e.getMessage, "already exists") =>
        logInfo(s"Consumer group exists: $groupName")
        orElse()
    }
  }
}
