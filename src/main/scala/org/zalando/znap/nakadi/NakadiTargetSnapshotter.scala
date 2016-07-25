/**
  *
  * Copyright (C) 2016 Zalando SE
  *
  * This software may be modified and distributed under the terms
  * of the MIT license.  See the LICENSE file for details.
  */
package org.zalando.znap.nakadi

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.AskTimeoutException
import org.zalando.znap.config.{Config, NakadiTarget}
import org.zalando.znap.disk.DiskPersistor
import org.zalando.znap.nakadi.GetPartitionsWorker.Partitions
import org.zalando.znap.nakadi.objects.EventBatch
import org.zalando.znap.utils.{ActorNames, NoUnexpectedMessages, TimeoutException}

import scala.concurrent.duration.FiniteDuration

/**
  * Root snapshotter for a Nakadi target.
  */
class NakadiTargetSnapshotter(nakadiTarget: NakadiTarget,
                              config: Config,
                              tokens: NakadiTokens) extends Actor
    with NoUnexpectedMessages with ActorLogging {

  import NakadiTargetSnapshotter._
  import org.zalando.znap.utils._
  import akka.pattern.{ask, pipe}
  import context.dispatcher

  private val diskPersistor = context.actorOf(Props(
    classOf[DiskPersistor], nakadiTarget, config
  ))

  override def preStart(): Unit = {
    log.info(s"Starting snapshotter for target $nakadiTarget")

    implicit val timeout = config.DefaultAskTimeout
    val getPartitionsWorker = context.actorOf(
      Props(classOf[GetPartitionsWorker], nakadiTarget, config, tokens),
      s"GetPartitionsWorker-${ActorNames.randomPart()}")
    val f = getPartitionsWorker ? GetPartitionsWorker.GetPartitionsCommand
    f.pipeTo(self)

    diskPersistor ! DiskPersistor.InitCommand
  }

  def initialization: Receive = {
    case Partitions(partitions) =>
      log.info(s"Got partitions for Nakadi target ${nakadiTarget.host}:${nakadiTarget.port}/${nakadiTarget.eventType}: $partitions")
      diskPersistor ! DiskPersistor.AcceptPartitionsCommand(partitions)

      context.system.scheduler.scheduleOnce(
        config.Persistence.SnapshotInitTimeout,
        self,
        PersistorAcceptPartitionsTimeout(config.Persistence.SnapshotInitTimeout))

    case DiskPersistor.PartitionsAccepted(partitionAndLastOffsetList) =>
      partitionAndLastOffsetList.foreach { partitionAndLastOffset =>
        context.actorOf(
          Props(classOf[NakadiReader],
            partitionAndLastOffset.partition,
            // TODO: make initial offset configurable (e.g. restart from start)
            partitionAndLastOffset.lastOffset,
            nakadiTarget, config, tokens),
          s"NakadiReader-${nakadiTarget.id}-${partitionAndLastOffset.partition}-${partitionAndLastOffset.lastOffset}-${ActorNames.randomPart()}"
        )
      }
      context.become(waitingForEventBatch)

    case scala.util.Failure(ex: AskTimeoutException) =>
      throw ex

    case PersistorAcceptPartitionsTimeout(t) =>
      throw new TimeoutException(s"Disk persistor initialization timeout ($t) for target $nakadiTarget.")
  }

  def waitingForEventBatch: Receive = {
    case PersistorAcceptPartitionsTimeout(t) =>
      ignore()

    case event: EventBatch =>
      diskPersistor forward event
  }

  override def receive: Receive = initialization
}

object NakadiTargetSnapshotter {
  trait LocalTimeout
  final case class PersistorAcceptPartitionsTimeout(timeout: FiniteDuration) extends LocalTimeout
}
