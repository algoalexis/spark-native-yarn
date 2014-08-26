package org.apache.spark

import java.nio.ByteBuffer
import org.apache.spark.scheduler.ResultTask
import org.apache.spark.scheduler.Task
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.storage.BlockManager
import org.apache.spark.scheduler.ResultTask
import sun.misc.Unsafe
import org.apache.spark.rdd.CoGroupPartition
import java.io.InputStream
import com.hortonworks.spark.tez.utils.TypeAwareStreams.TypeAwareObjectInputStream
import java.io.ByteArrayInputStream
import sun.reflect.ReflectionFactory
import java.io.ObjectInputStream
import org.apache.spark.tez.VertexTask
import org.apache.spark.tez.VertexTask

object SparkUtils {
  val sparkConf = new SparkConf
  val unsafeConstructor = classOf[Unsafe].getDeclaredConstructor();
  unsafeConstructor.setAccessible(true);
  val unsafe = unsafeConstructor.newInstance();

  
  def createUnsafeInstance[T](clazz:Class[T]):T = {
//    ReflectionFactory.getReflectionFactory().
    //unsafe.defineClass(x$1, x$2, x$3, x$4)
    unsafe.allocateInstance(clazz).asInstanceOf[T]
  }

  def createSparkEnv(shuffleManager:TezShuffleManager) {
    val blockManager = unsafe.allocateInstance(classOf[BlockManager]).asInstanceOf[BlockManager];   
    val ser = new JavaSerializer(sparkConf)
    val se = new SparkEnv("0", null, ser, ser, null, null, shuffleManager, null, blockManager, null, null, null, null, null, null, sparkConf)
    SparkEnv.set(se)
  }

  def deserializeSparkTask(taskBytes: Array[Byte], partitionId:Int): VertexTask = {
    val serializer = SparkEnv.get.serializer.newInstance
    val taskBytesBuffer = ByteBuffer.wrap(taskBytes)
    val task = serializer.deserialize[VertexTask](taskBytesBuffer)

    task
  }
  
//  def deserializeSparkTask(inputBytes: Array[Byte], partitionId:Int): Task[_] = {
//    val serializer = SparkEnv.get.serializer.newInstance
//    val is = new TypeAwareObjectInputStream(new ByteArrayInputStream(inputBytes))
//    serializer.deserializeStream(is)
//    val task = is.readObject.asInstanceOf[Task[_]]
//    if (task.isInstanceOf[TezShuffleTask]){
//      task.asInstanceOf[TezShuffleTask].resetPartition(partitionId)
//    }
//    task
//  }

  def runTask(task: VertexTask) = {
    
    task.runTask
//    val v = task.asInstanceOf[Task[_]].runTask(new TaskContext(0, 1, 1, true))
//    if (v.isInstanceOf[Array[Tuple2[_,_]]]){
//      val kvWriter = SparkEnv.get.shuffleManager.getWriter[Any,Any](null, 0, null)
//      for (x <- v.asInstanceOf[Array[Tuple2[_,_]]]){
//        kvWriter.write(x.asInstanceOf[Product2[_,_]]);
//      }
//    }
//    else if (v.isInstanceOf[Option[_]]){
//      val kvWriter = SparkEnv.get.shuffleManager.getWriter[Any,Any](null, 0, null)
//      println(kvWriter)
//      kvWriter.write((0, v).asInstanceOf[Product2[_,_]])
//    }
//    v
  }
}