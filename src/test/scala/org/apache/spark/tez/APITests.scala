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

import org.junit.Test
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.IntWritable
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat
import org.apache.spark.SparkConf
import org.junit.Assert
import org.apache.commons.io.FileUtils
import java.io.File
import org.apache.spark.tez.test.utils.TestUtils
import org.apache.spark.HashPartitioner
import org.apache.hadoop.mapred.lib.MultipleTextOutputFormat
import org.apache.hadoop.io.NullWritable

/**
 * Will run in Tez local mode
 */
class APITests {

  @Test
  def reduceByKey() {
    val applicationName = "reduceByKey"
    val sparkConf = this.buildSparkConf
    sparkConf.setAppName(applicationName)
    val sc = new SparkContext(sparkConf)
    val source = sc.textFile("src/test/scala/org/apache/spark/tez/sample.txt")
    
    // ===
    val result = source
      .flatMap(x => x.split(" "))
      .map(x => (x, 1))
      .reduceByKey((x, y) => x + y)
      .saveAsNewAPIHadoopFile(applicationName + "_out", classOf[Text],
        classOf[IntWritable], classOf[TextOutputFormat[_, _]])
    // ===
        
    TestUtils.printSampleResults(applicationName + "_out")
    sc.stop
    this.cleanUp(applicationName)
  }

  @Test
  def count() {
    val applicationName = "count"
    val sparkConf = this.buildSparkConf
    sparkConf.setAppName(applicationName)
    val sc = new SparkContext(sparkConf)
    val source = sc.textFile("src/test/scala/org/apache/spark/tez/sample.txt")
    
    // ===
    val result = source
      .flatMap(x => x.split(" "))
      .map(x => (x, 1))
      .reduceByKey((x, y) => x + y)
      .count
    // ===  
    
    Assert.assertEquals(51, result)
    sc.stop
    this.cleanUp(applicationName)
  }

  @Test
  def join() {
    val file1 = "src/test/scala/org/apache/spark/tez/file1.txt"
    val file2 = "src/test/scala/org/apache/spark/tez/file2.txt"
    val applicationName = "join"
    val sparkConf = this.buildSparkConf
    sparkConf.setAppName(applicationName)
    val sc = new SparkContext(sparkConf)
    val source1 = sc.textFile(file1)
    val source2 = sc.textFile(file2)

    // ===
    val two = source2.map { x =>
      val s = x.split(" ")
      val key: Int = Integer.parseInt(s(0))
      (key, s(1))
    }
    val result = source1.map { x =>
      val s = x.split(" ")
      val key: Int = Integer.parseInt(s(2))
      val t = (key, (s(0), s(1)))
      t
    }.join(two).reduceByKey { (x, y) => ((x._1.toString, y._1.toString), x._2)
    }.saveAsNewAPIHadoopFile(applicationName + "_out", classOf[IntWritable], classOf[Text], classOf[TextOutputFormat[_, _]])
    // ===
    
    sc.stop   
    TestUtils.printSampleResults(applicationName + "_out")
    this.cleanUp(applicationName)
  }
  
  @Test
  def partitionBy() {
    val applicationName = "partitionBy"
    val sparkConf = this.buildSparkConf
    sparkConf.setAppName(applicationName)
    val sc = new SparkContext(sparkConf)
    val source = sc.textFile("src/test/scala/org/apache/spark/tez/partitioning.txt")
    
    // ===
    val result = source
    	.map{s => val split = s.split("\\s+", 2); (split(0).replace(":", "_"), split(1))}
    	.partitionBy(new HashPartitioner(2))
    	.saveAsHadoopFile(applicationName + "_out", classOf[Text], classOf[Text], classOf[KeyPerPartitionOutputFormat])
    // ===
        
    TestUtils.printSampleResults(applicationName + "_out")
    sc.stop
    this.cleanUp(applicationName)
  }
  
  @Test
  def cache() {
    val applicationName = "cache"
    val sparkConf = this.buildSparkConf
    sparkConf.setAppName(applicationName)
    val sc = new SparkContext(sparkConf)
    val source = sc.textFile("src/test/scala/org/apache/spark/tez/sample.txt")
    
    // ===
    val result = source
      .flatMap(x => x.split(" "))
      .map(x => (x, 1))
      .reduceByKey((x, y) => x + y)
      .cache
     
    // ===
        
    TestUtils.printSampleResults(result.name)
    sc.stop
    this.cleanUp(applicationName)
  }

  /**
   * 
   */
  def cleanUp(applicationname: String) {
    FileUtils.deleteDirectory(new File(applicationname + "_out"))
  }

  /**
   *
   */
  def buildSparkConf(): SparkConf = {
    val masterUrl = "execution-context:" + classOf[TezJobExecutionContext].getName
    val sparkConf = new SparkConf
    sparkConf.set("spark.ui.enabled", "false")
    sparkConf.setMaster(masterUrl)
    sparkConf
  }
}

/**
 * 
 */
class KeyPerPartitionOutputFormat extends MultipleTextOutputFormat[Any, Any] {
  override def generateActualKey(key: Any, value: Any): Any = {
    NullWritable.get()
  }

  override def generateFileNameForKeyValue(key: Any, value: Any, name: String): String = {
    key.asInstanceOf[String]
  }
}