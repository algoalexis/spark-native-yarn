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

import scala.collection.JavaConverters.asJavaCollectionConverter
import scala.reflect.ClassTag
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.spark.Logging
import org.apache.spark.Partition
import org.apache.spark.ShuffleDependency
import org.apache.spark.SparkEnv
import org.apache.spark.TaskContext
import org.apache.spark.rdd.ParallelCollectionPartition
import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.Stage
import org.apache.tez.client.TezClient
import org.apache.tez.dag.api.TezConfiguration
import org.apache.spark.tez.io.TypeAwareStreams.TypeAwareObjectOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.lang.Boolean
import org.apache.spark.tez.io.TezRDD
import java.io.FileNotFoundException
import org.apache.hadoop.io.Writable
import org.apache.spark.rdd.ShuffledRDD
import org.apache.spark.tez.utils.ReflectionUtils
import org.apache.spark.Partitioner
import org.apache.hadoop.yarn.api.records.LocalResource
import org.apache.spark.rdd.ParallelCollectionRDD
import java.util.HashMap
import scala.collection.JavaConverters._
import org.apache.spark.tez.io.TezRDD
import org.apache.hadoop.io.NullWritable
import org.apache.spark.tez.io.CacheRDD

/**
 * Utility class used as a gateway to DAGBuilder and DAGTask
 */
class Utils[T, U: ClassTag](stage: Stage, func: (TaskContext, Iterator[T]) => U, 
    localResources:java.util.Map[String, LocalResource] = new java.util.HashMap[String, LocalResource]) extends Logging {

  private var vertexId = 0;
  
  private val sparkContext = stage.rdd.context
  
  private val tezConfiguration = new TezConfiguration
  
  private val dagBuilder = new DAGBuilder(stage.rdd.context.appName, this.localResources, tezConfiguration)
  
  private val fs = FileSystem.get(tezConfiguration)

  /**
   * 
   */
  def build(returnType:ClassTag[U], keyClass:Class[_ <:Writable], valueClass:Class[_ <:Writable], outputFormatClass:Class[_], outputPath:String):DAGTask = {
    this.prepareDag(returnType, stage, null, func, keyClass, valueClass)
    val dagTask = dagBuilder.build(keyClass, valueClass, outputFormatClass, outputPath)
    val vertexDescriptors = dagBuilder.getVertexDescriptors().entrySet().asScala
    logInfo("DAG: " + dagBuilder.toString())
    dagTask
  }

  /**
   * 
   */
  private def prepareDag(returnType:ClassTag[U], stage: Stage, dependentStage: Stage, func: (TaskContext, Iterator[T]) => U, keyClass:Class[_], valueClass:Class[_]) {
    if (stage.parents.size > 0) {
      val missing = stage.parents.sortBy(_.id)
      for (parent <- missing) {
        prepareDag(returnType, parent, stage, func, keyClass, valueClass)
      }
    }

    val partitioner = ReflectionUtils.getFieldValue(stage.rdd, "partitioner").asInstanceOf[Option[Partitioner]]
    
    if (partitioner.isDefined){
      dagBuilder.addPartitioner(partitioner.get)
    }
     
    val vertexTask =
      if (stage.isShuffleMap) {
        logInfo(stage.shuffleDep.get.toString)
        logInfo("STAGE Shuffle: " + stage + " vertex: " + this.vertexId)
        val initialRdd = stage.rdd.getNarrowAncestors.sortBy(_.id).head
        if (initialRdd.isInstanceOf[ParallelCollectionRDD[_]]){
          new VertexShuffleTask(stage.id, stage.rdd, stage.shuffleDep.asInstanceOf[Option[ShuffleDependency[Any, Any, Any]]], stage.rdd.partitions)
        } else {
          new VertexShuffleTask(stage.id, stage.rdd, stage.shuffleDep.asInstanceOf[Option[ShuffleDependency[Any, Any, Any]]], Array(stage.rdd.partitions(0)))
        }
      } else {
        logInfo("STAGE Result: " + stage + " vertex: " + this.vertexId)
        val narrowAncestors = stage.rdd.getNarrowAncestors.sortBy(_.id)
        if (narrowAncestors.size > 0 &&  narrowAncestors.head.isInstanceOf[ParallelCollectionRDD[_]]) {
          if (classOf[Unit].isAssignableFrom(returnType.runtimeClass)) {
            new VertexResultTask(stage.id, stage.rdd.asInstanceOf[RDD[T]], stage.rdd.partitions)
          } else {
            new VertexResultTask(stage.id, stage.rdd.asInstanceOf[RDD[T]], stage.rdd.partitions, func)
          }
        } else {
          if (classOf[Unit].isAssignableFrom(returnType.runtimeClass)) {
            new VertexResultTask(stage.id, stage.rdd.asInstanceOf[RDD[T]], Array(stage.rdd.partitions(0)))
          } else {
            new VertexResultTask(stage.id, stage.rdd.asInstanceOf[RDD[T]], Array(stage.rdd.partitions(0)), func)
          }
        }
      }
    
    var dependencies = stage.rdd.getNarrowAncestors.sortBy(_.id)
   
    val deps = 
      if (stage.rdd.isInstanceOf[CacheRDD[_]]){
        stage.rdd
      } else {
        if (dependencies.size == 0 || dependencies(0).name == null) (for (parent <- stage.parents) yield parent.id).asJavaCollection else dependencies(0)
      }

    val vd = new VertexDescriptor(stage.id, vertexId, deps, vertexTask)
    vd.setNumPartitions(stage.numPartitions)
    dagBuilder.addVertex(vd)

    vertexId += 1
  }
}
