/**
  *
  * Copyright (C) 2016 Zalando SE
  *
  * This software may be modified and distributed under the terms
  * of the MIT license.  See the LICENSE file for details.
  */
package org.zalando.znap.config

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import com.amazonaws.services.s3.AmazonS3Client
import com.typesafe.config.{ConfigParseOptions, Config => TypesafeConfig, ConfigFactory => TypesafeConfigFactory}
import org.apache.http.client.utils.URIBuilder
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

object Config {
  import scala.collection.JavaConversions._

  /** root application config */
  private val appConfig = TypesafeConfigFactory
    .systemProperties()
    .withFallback(readInstanceConfig())
    .withFallback(TypesafeConfigFactory.defaultApplication().resolve())


  val ApplicationInstanceId = {
    val now = LocalDateTime.now(ZoneId.of("UTC"))
    val random = UUID.randomUUID().toString.replace("-", "").take(12)
    val format = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    s"${format.format(now)}-$random"
  }

  // Application config.

  object Akka {
    val DynamoDBDispatcher = "dynamodb-dispatcher"
  }


  object Tokens {
    val AccessToken = appConfig.getString("tokens.accessToken")
    val TokenInfo = appConfig.getString("tokens.tokenInfo")
  }

  object Nakadi {
    val PartitionsReadTimeout = {
      val t = appConfig.getDuration("nakadi.partitionsReadTimeout")
      FiniteDuration(t.toMillis, TimeUnit.MILLISECONDS)
    }
  }

  val DefaultAskTimeoutDuration = appConfig.getDuration("akka.defaultAskTimeout")
  val DefaultAskTimeout = Timeout(DefaultAskTimeoutDuration.toMillis, TimeUnit.MILLISECONDS)
  val HttpStreamingMaxSize = appConfig.getBytes("http.streamingMaxSize")

  object Persistence {
    object Disk {
      val SnapshotInitTimeout = {
        val t = appConfig.getDuration("persistence.disk.snapshotInitTimeout")
        FiniteDuration(t.toMillis, TimeUnit.MILLISECONDS)
      }

      val SnapshotInterval = {
        val t = appConfig.getDuration("persistence.disk.snapshotInterval")
        FiniteDuration(t.toMillis, TimeUnit.MILLISECONDS)
      }

      // TODO dirs part of persistence global config. (folders has to be backed by EBS)
      object Paths {
        val WorkingDirectory = appConfig.getString("persistence.disk.workingDirectory")
        val SnapshotsDirectory = appConfig.getString("persistence.disk.snapshotsDirectory")
      }
    }
  }

  object Supervision {

//    object RootExtractor {
//      val MaxFailures = config.getInt("extraction.supervision.rootExtractor.maxFailures")
//      val Period = config.getDuration("extraction.supervision.rootExtractor.period")
//    }

    object NakadiReader {
      val MaxFailures = appConfig.getInt("supervision.nakadiReader.maxFailures")
      val Period = appConfig.getDuration("supervision.nakadiReader.period")
    }

  }


  // Snapshots config.


  val Targets: List[SnapshotTarget] = {
    appConfig.getObjectList("snapshotting.targets").toList.map(co => readSnapshotTarget(co.toConfig))
  }

  private def readSnapshotTarget(configObject: TypesafeConfig): SnapshotTarget = {
    val source = {
      val sourceConfig = configObject.getObject("source").toConfig
      sourceConfig.getString("type") match {
        case "nakadi" =>
          val nakadiURI = {
            val nakadiURIBuilder = new URIBuilder(sourceConfig.getString("url"))
            nakadiURIBuilder.setPort(resolvePort(nakadiURIBuilder.getScheme, nakadiURIBuilder.getPort))
            nakadiURIBuilder.build()
          }

          val eventType = sourceConfig.getString("event-type")
          val eventClass = sourceConfig.getString("event-class")
          NakadiSource(nakadiURI, eventType, eventClass)
      }
    }

    val destination = {
      val destConfig = configObject.getObject("destination").toConfig
      destConfig.getString("type") match {
        case "dynamodb" =>
          val restURIBuilder = new URIBuilder(destConfig.getString("url"))
          restURIBuilder.setPort(resolvePort(restURIBuilder.getScheme, restURIBuilder.getPort))

          val tableName = destConfig.getString("table-name")
          val offsetsTableName = destConfig.getString("offsets-table-name")
          DynamoDBDestination(restURIBuilder.build(), tableName, offsetsTableName)
      }
    }

    val key = configObject.getString("key").split('.').toList
    val compress = configObject.getBoolean("compress")

    SnapshotTarget(source, destination, key, compress)
  }

  private def resolvePort(scheme: String, port: Int): Int =
    (scheme, port) match {
      case ("http", -1)  => 80
      case ("https", -1) => 443
      case _ => port
    }

  object DynamoDB {
    object KVTables {
      object Attributes {
        val Key = "key"
        val Value = "value"
      }
    }

    object Batches {
      val WriteBatchSize = 25
    }
  }


  private def readInstanceConfig(): TypesafeConfig = {
    val configFile = System.getenv("ZNAP_CONFIG_FILE")
    if (configFile == null) {
      TypesafeConfigFactory.empty()
    } else {
      val parts = configFile.split("://", 2)
      if (parts.length < 2) {
//        logger.info(s"No scheme is set for config file URL, considered as file path.")
        readInstanceConfigFromFile(configFile)
      } else {
        parts(0).toLowerCase() match {
          case "s3" =>
            readInstanceConfigFromS3(configFile)

          case unknown =>
            throw new Exception(s"""Unknown scheme "$unknown" for config file URL: "$configFile"""")
        }
      }
    }
  }

  private def readInstanceConfigFromS3(configFile: String): TypesafeConfig = {
    val Array(_, path) = configFile.split("://", 2)
    val parts = path.split("/", 2)
    if (parts.length < 2) {
      throw new Exception(s"""Incorrect format of s3 URL for config file URL: "$configFile". Correct format: "s3://<bucket>/<key1>[/<key2>/...]"""")
    } else {
      val Array(bucket, key) = parts
      val s3Client = new AmazonS3Client()
      val content = s3Client.getObjectAsString(bucket, key)
      TypesafeConfigFactory.parseString(content)
    }
  }

  private def readInstanceConfigFromFile(configFile: String): TypesafeConfig = {
    val file = new File(configFile)
    val parseOptions = ConfigParseOptions.defaults().setAllowMissing(false)
    TypesafeConfigFactory.parseFile(file, parseOptions)
  }
}
