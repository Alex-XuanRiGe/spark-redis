package com.redislabs.provider.redis.util

import org.apache.commons.lang3.StringUtils
import redis.clients.jedis.{EntryID, Jedis}

/**
  * @author The Viet Nguyen
  */
object StreamUtils extends Logging {

  val EntryIdEarliest = new EntryID(0, 0)

  def createConsumerGroupIfNotExist(conn: Jedis, streamKey: String, groupName: String,
                                    offset: EntryID, reset: Boolean = false): Unit = {
    try {
      conn.xgroupCreate(streamKey, groupName, offset, true)
    } catch {
      case e: Exception if StringUtils.contains(e.getMessage, "already exists") =>
        logInfo(s"Consumer group exists: $groupName")
        if (reset) {
          logInfo(s"Reset consumer group: $groupName, start: $offset")
          conn.xgroupSetID(streamKey, groupName, offset)
        }
    }
  }
}
