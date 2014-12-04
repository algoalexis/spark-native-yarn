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
package org.apache.spark.tez.io

import org.apache.spark.shuffle.ShuffleReader
import org.apache.tez.runtime.api.LogicalInput
import org.apache.tez.runtime.api.Reader
import org.apache.tez.runtime.library.api.KeyValuesReader
import org.apache.tez.runtime.library.api.KeyValueReader
import org.apache.spark.shuffle.BaseShuffleHandle
import org.apache.spark.TaskContext
import org.apache.hadoop.io.Writable
import java.util.Map
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.IntWritable
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.conf.Configuration
import org.apache.tez.dag.api.TezConfiguration
import org.apache.spark.InterruptibleIterator
import org.apache.spark.util.NextIterator
import java.io.IOException
import scala.collection.JavaConverters._
import org.apache.spark.TaskContextImpl
import org.apache.spark.serializer.Serializer
import org.apache.spark.Logging
import org.apache.spark.Aggregator

/**
 * Implementation of Spark's ShuffleReader which delegates it's read functionality to Tez
 * This implementation is tailored for after-shuffle reads (e.g., ResultTask)
 */
class TezShuffleReader[K, C](reader: KeyValuesReader, val handle: BaseShuffleHandle[K, _, C])
  extends ShuffleReader[K, C] {
//  private val inputIndex = input.keySet().iterator().next()
//  private val reader = input.remove(this.inputIndex).getReader().asInstanceOf[KeyValuesReader]
  private val dep = handle.dependency

  /**
   *
   */
  override def read(): Iterator[Product2[K, C]] = {
    val dep = handle.dependency
    val aggregator = if (dep.aggregator.isDefined) dep.aggregator.get else null
    new ShuffleIterator(this.reader, aggregator.asInstanceOf[Aggregator[Any, Any, Any]]).asInstanceOf[Iterator[Product2[K,C]]].map(pair => (pair._1, pair._2))
  }
  
  /**
   *
   */
  def stop = ()
}

/**
 *
 */
private class ShuffleIterator[K, C](reader: KeyValuesReader, aggregator:Aggregator[Any,Any,Any]) extends Iterator[Product2[Any, Any]] {
  private var hasNextNeverCalled = true
  private var containsNext = false;
  private var shoudlCheckHasNext = false;
  private var currentValues: Iterator[Object] = _

  /**
   *
   */
  override def hasNext(): Boolean = {
    if (this.hasNextNeverCalled || shoudlCheckHasNext) {
      this.hasNextNeverCalled = false
      this.containsNext = this.doHasNext
    }
    this.containsNext
  }

  /**
   *
   */
  override def next(): Product2[Any, Any] = {
    if (hasNextNeverCalled) {
      this.hasNext
    }

    /*
     * Unlike Spark native we don't need to maintain a map with spill capabilities to perform the 
     * aggregation of the entire iterator. We only aggregate on the per-key basis since we can rely 
     * on the result of the YARN shuffle which gives us KV entries sorted by key.
     */
    if (this.containsNext) {
      if (aggregator != null) {
        this.currentValues = this.reader.getCurrentValues().iterator.asScala
        var mergedValue: Any = null
        for (value <- this.currentValues) {
          val vw = value.asInstanceOf[ValueWritable]
          val v = vw.getValue()
          if (mergedValue == null) {
            mergedValue = aggregator.createCombiner(v)
          } else {
            mergedValue = aggregator.mergeValue(mergedValue, v)
          }
        }
        val key = this.reader.getCurrentKey.asInstanceOf[KeyWritable].getValue().asInstanceOf[Comparable[_]]
        val result = (key, mergedValue.asInstanceOf[Object])
        this.shoudlCheckHasNext = true
        result
      } else {
        if (this.currentValues == null) {
          this.currentValues = this.reader.getCurrentValues().iterator.asScala
        }
        val key = this.reader.getCurrentKey.asInstanceOf[KeyWritable].getValue().asInstanceOf[Comparable[_]]
        val result = (key, this.currentValues.next.asInstanceOf[ValueWritable].getValue())
        if (!this.currentValues.hasNext) {
          this.shoudlCheckHasNext = true
          this.currentValues = null
        }
        result
      }
    } else {
      throw new IllegalStateException("Reached the end of the iterator. " +
        "Calling hasNext() prior to next() would avoid this exception")
    }
  }

  /**
   *
   */
  private def doHasNext(): Boolean = {
    this.shoudlCheckHasNext = false
    this.reader.next
  }
}

/**
 * Wrapper over Tez's KeyValuesReader which uses semantics of java.util.Iterator
 * while giving you access to "current key" and "next value" contained in KeyValuesReader's ValuesIterator.
 */
private class KVSIterator(kvReader: KeyValuesReader) {
  var vIter: java.util.Iterator[TypeAwareWritable[_]] = _

  /**
   * Checks if underlying reader contains more data to read.
   */
  def hasNext = {
    if (vIter != null && vIter.hasNext()) {
      true
    } else {
      if (kvReader.next()) {
        vIter = kvReader.getCurrentValues().iterator().asInstanceOf[java.util.Iterator[TypeAwareWritable[_]]]
        true
      } else {
        false
      }
    }
  }

  /**
   * Returns the next value in the current ValuesIterator
   */
  def nextValue() = {
    vIter.next().getValue()
  }

  /**
   * For this case 'ket' will always be represented by TypeAwareWritable (KeyWritable)
   * since its source is the result of YARN shuffle and the preceeding writer will
   * write all intermediate outputs as TypeAwareWritable for both keys and values.
   */
  def getCurrentKey = {
    kvReader.getCurrentKey().asInstanceOf[TypeAwareWritable[_]].getValue()
  }
}