/**
  *
  * Copyright (C) 2016 Zalando SE
  *
  * This software may be modified and distributed under the terms
  * of the MIT license.  See the LICENSE file for details.
  */
package org.zalando.znap.persistence.dynamo

import com.amazonaws.services.dynamodbv2.document.{DynamoDB, Item, TableWriteItems}
import org.slf4j.LoggerFactory
import org.zalando.znap.config.{Config, DynamoDBDestination, SnapshotTarget}
import org.zalando.znap.source.nakadi.objects.Cursor
import org.zalando.znap.persistence.OffsetWriterSync

import scala.concurrent.{ExecutionContext, Future}

class DynamoDBOffsetWriter(snapshotTarget: SnapshotTarget,
                           override protected val dynamoDB: DynamoDB) extends OffsetWriterSync
      with DynamoDBWriter {
  private val logger = LoggerFactory.getLogger(classOf[DynamoDBOffsetWriter])

  private val dynamoDBDestination: DynamoDBDestination = snapshotTarget.destination.asInstanceOf[DynamoDBDestination]

  override def init(): Unit = {}

  override def write(cursor: Cursor): Unit = {
    val offsetUpdateItems = new TableWriteItems(dynamoDBDestination.offsetsTableName)

    offsetUpdateItems.addItemToPut(new Item()
      .withPrimaryKey(Config.DynamoDB.KVTables.Attributes.Key, cursor.partition)
      .withString(Config.DynamoDB.KVTables.Attributes.Value, cursor.offset)
    )
    writeWithRetries(offsetUpdateItems)
  }
}