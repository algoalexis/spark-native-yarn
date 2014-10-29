/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
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
package org.apache.spark.tez

import java.io.Serializable
import scala.reflect.ClassTag
import org.apache.spark.broadcast.Broadcast
import java.util.UUID
import org.apache.tez.dag.api.TezConfiguration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import java.net.URL
import java.io.FileOutputStream
import java.io.Closeable
import java.io.InputStream

/**
 * Tez specific implementation of Broadcast which uses HDFS as its broadcasting mechanism.
 */
class TezBroadcast[T: ClassTag](@transient var broadcastedValue: T, applicationName: String) extends Broadcast[T](0L) {

  private val path: String = applicationName + "/broadcast/" + UUID.randomUUID().toString() + ".ser"

  /**
   *
   */
  private[tez] def broadcast() {
    val fs = FileSystem.get(new TezConfiguration)
    if (!fs.exists(new Path(path))) {
      this.saveToHdfs()
    }
  }

  /**
   *
   */
  override protected def getValue() = {
    if (this.broadcastedValue == null) {
      val fs = FileSystem.get(new TezConfiguration)
      var bValis = fs.open(new Path(path))
      this.broadcastedValue = SparkUtils.deserialize(bValis).asInstanceOf[T]
    }
    this.broadcastedValue
  }

  /**
   *
   */
  override protected def doUnpersist(blocking: Boolean) {
    this.remove
  }

  /**
   *
   */
  override protected def doDestroy(blocking: Boolean) {
    this.remove
  }

  /**
   *
   */
  private def remove() {
    val fs = FileSystem.get(new TezConfiguration)
    fs.delete(new Path(path))
  }

  /**
   *
   */
  private def saveToHdfs() {
    val fs = FileSystem.get(new TezConfiguration)
    SparkUtils.serializeToFs(value, fs, new Path(path))
  }
}