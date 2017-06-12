/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License") you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.streaming.aliyun.dts

import java.util
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._
import scala.collection.mutable

import com.aliyun.drc.clusterclient.message.ClusterMessage
import com.aliyun.drc.clusterclient.{ClusterListener, RegionContext, DefaultClusterClient}

import org.apache.spark.Logging
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.receiver.{Receiver, BlockGeneratorListener, BlockGenerator}

private[dts] case class ClusterMessages(messages: Array[ClusterMessage]) {
  def isEmpty(): Boolean = messages.isEmpty

  def nonEmpty(): Boolean = messages.nonEmpty

  override def toString(): String = messages.mkString("ClusterMessages(", ", ", ")")
}

class BinlogReceiver(
    accessKeyId: String,
    accessKeySecret: String,
    guid: String,
    func: ClusterMessage => String,
    storageLevel: StorageLevel,
    usePublicIp: Boolean = false)
  extends Receiver[String](storageLevel) with Logging {
  receiver =>

  /**
   * Manage the BlockGenerator in receiver itself for better managing block store and offset
   * commit.
   */
  private var blockGenerator: BlockGenerator = null

  private var clusterMessageInCurrentBlock: mutable.ArrayBuffer[ClusterMessage] = null

  private var blockIdToClusterMessages: ConcurrentHashMap[StreamBlockId, ClusterMessages] = null

  @transient var client: DefaultClusterClient = null

  override def onStart(): Unit = {
    clusterMessageInCurrentBlock = new mutable.ArrayBuffer[ClusterMessage]
    blockIdToClusterMessages = new ConcurrentHashMap[StreamBlockId, ClusterMessages]
    val context = new RegionContext()
    context.setUsePublicIp(usePublicIp)
    context.setAccessKey(accessKeyId)
    context.setSecret(accessKeySecret)
    client = new DefaultClusterClient(context)
    val listener = new ClusterListener {
      override def notify(messages: util.List[ClusterMessage]): Unit = {
        messages.foreach(message => {
          blockGenerator.addMultipleDataWithCallback(Iterator(message.getRecord.getId), message)
        })
      }

      override def noException(e: Exception): Unit = {
        // todo: add process for exception
        // do nothing
      }
    }
    client.addConcurrentListener(listener)
    client.askForGUID(guid)
    blockGenerator = supervisor.createBlockGenerator(new GeneratedBlockHandler)
    blockGenerator.start()
    client.start()
  }

  override def onStop(): Unit = {
    if (client != null) {
      client.stop()
      client = null
    }

    if (blockGenerator != null) {
      blockGenerator.stop()
      blockGenerator = null
    }

    if (clusterMessageInCurrentBlock != null) {
      clusterMessageInCurrentBlock.clear()
      clusterMessageInCurrentBlock = null
    }

    if (blockIdToClusterMessages != null) {
      blockIdToClusterMessages.clear()
      blockIdToClusterMessages = null
    }
  }

  private def rememberAddedClusterMessage(message: ClusterMessage): Unit = {
    clusterMessageInCurrentBlock += message
  }

  private def rememberBlockMessages(blockId: StreamBlockId): Unit = {
    blockIdToClusterMessages.put(blockId, ClusterMessages(clusterMessageInCurrentBlock.toArray))
    clusterMessageInCurrentBlock.clear()
    logDebug(s"Generated block $blockId.")
  }

  private def storeBlockAndCommitMessage(
                                          blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[_]): Unit = {
    var count = 0
    var pushed = false
    var exception: Exception = null
    while (!pushed && count <= 3) {
      try {
        store(blockIdToClusterMessages.get(blockId).messages.iterator.map(func))
        pushed = true
      } catch {
        case ex: Exception =>
          count += 1
          exception = ex
      }
    }
    if (pushed) {
      Option(blockIdToClusterMessages.get(blockId)).foreach(commitOffset)
      blockIdToClusterMessages.remove(blockId)
    } else {
      stop("Error while storing block into Spark", exception)
    }
  }

  private def commitOffset(messages: ClusterMessages): Unit = {
    messages.messages.foreach(e => {
      e.ackAsConsumed()
    })
  }

  /** Class to handle blocks generated by the block generator. */
  private final class GeneratedBlockHandler extends BlockGeneratorListener {

    def onAddData(data: Any, metadata: Any): Unit = {
      rememberAddedClusterMessage(metadata.asInstanceOf[ClusterMessage])
    }

    def onGenerateBlock(blockId: StreamBlockId): Unit = {
      rememberBlockMessages(blockId)
    }

    def onPushBlock(blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[_]): Unit = {
      storeBlockAndCommitMessage(blockId, arrayBuffer)
    }

    def onError(message: String, throwable: Throwable): Unit = {
      reportError(message, throwable)
    }
  }
}