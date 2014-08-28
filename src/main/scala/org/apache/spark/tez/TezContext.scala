package org.apache.spark.tez

import scala.reflect.ClassTag
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.InputFormat
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.spark.InterruptibleIterator
import org.apache.spark.Logging
import org.apache.spark.Partition
import org.apache.spark.SparkConf
import org.apache.spark.SparkEnv
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.Stage
import org.apache.spark.util.CallSite
import org.apache.tez.dag.api.TezConfiguration
import org.apache.spark.ShuffleDependency
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.commons.io.FileUtils

/**
 * Source class which contains methods, fields and constructors required to instrument 
 * SparkContext to support Tez execution engine. 
 * See TezInstrumentationAgent for more details
 */
private abstract class TezContext(conf: SparkConf) {

  //  val hadoopConfiguration: Configuration = new TezConfiguration

  val isLocal = {
    false
  }

  /**
   * Override SparkContext's main 'runJob' method keeping most of its code
   * while avoiding communication with Spark's DAGScheduler and instead delegating to 
   * DAGBuilder to construct Tez DAGTask and execute it.
   */
  def runJob[T, U: ClassTag](
    rdd: RDD[T],
    func: (TaskContext, Iterator[T]) => U,
    partitions: Seq[Int],
    allowLocal: Boolean,
    resultHandler: (Int, U) => Unit) {

    val pf = new PostProcessFunction

    val ds = this.asInstanceOf[org.apache.spark.SparkContext].dagScheduler
    val method = ds.getClass.getDeclaredMethod("newStage", classOf[RDD[T]], classOf[Int], classOf[Option[ShuffleDependency[_, _, _]]], classOf[Int], classOf[CallSite])
    method.setAccessible(true)
    val stage = method.invoke(ds, rdd, new Integer(1), None, new Integer(0), org.apache.spark.util.Utils.getCallSite).asInstanceOf[Stage]
    val tezUtils = new Utils(stage, pf)

    val dagTask: DAGTask = tezUtils.build
    dagTask.execute

    // below is a temporary hack to satisfy Spark's requirement if user invoked 'collect' method
    // It will simply generate dummy array of tuples. Need to rework it.

    val res = Array(("", 0)).asInstanceOf[U]

    try {
      partitions.foreach(x => resultHandler(x, res))
    } catch {
      case e: Throwable => // ignore for now. TEMPORARY, not need to be here
    }
  }

  /**
   * Override SparkContext's 'parallelize' method by simply serializing partitions
   * which will transfered to HDFS as files essentially making collection processing 
   * the same as file processing
   * 
   * STUB for now
   */
  def parallelize[T: ClassTag](seq: Seq[T], numSlices: Int = 1): RDD[T] = {
    println("############# parallelize")
    //    new ParallelCollectionRDD[T](this, seq, numSlices, Map[Int, Seq[String]]())
    null
  }

  /**
   * Override SparkContext's 'textFile' method and delegates to new HADOOP API
   * rather then the original method supporting old.
   */
  def textFile(path: String, minPartitions: Int = 1): RDD[String] = {
    newAPIHadoopFile(path, classOf[TextInputFormat], classOf[LongWritable], classOf[Text]).map(pair => pair._2.toString)
  }

  /**
   * Override SparkContext's 'newAPIHadoopFile' method by creating TezRDD instead of HadoopRDD.
   * TezRDD overrides 'compute' methods to be compatible with Tez readers.
   */
  def newAPIHadoopFile[K, V, F <: InputFormat[K, V]](
    path: String,
    inputFormatClass: Class[F],
    keyClass: Class[K],
    valueClass: Class[V],
    conf: Configuration = new TezConfiguration(new YarnConfiguration)): RDD[(K, V)] = {

    val fs = FileSystem.get(conf);
    val qualifiedPath = fs.makeQualified(new Path(path)).toString()
    
    new TezRDD(qualifiedPath, this.asInstanceOf[org.apache.spark.SparkContext], inputFormatClass, keyClass, valueClass, new Configuration)
  }

  /**
   * Override another SparkContext's 'newAPIHadoopFile' delegating to the main one
   */
  def newAPIHadoopFile[K, V, F <: InputFormat[K, V]](path: String)(implicit km: ClassTag[K], vm: ClassTag[V], fm: ClassTag[F]): RDD[(K, V)] = {
    newAPIHadoopFile(
      path,
      fm.runtimeClass.asInstanceOf[Class[F]],
      km.runtimeClass.asInstanceOf[Class[K]],
      vm.runtimeClass.asInstanceOf[Class[V]])
  }
}
/**
 * Replacement for HadoopRDD.
 * Overrides 'compute' methods to be compatible with Tez readers.
 */
class TezRDD[K, V](
  path: String,
  sc: org.apache.spark.SparkContext,
  val inputFormatClass: Class[_ <: InputFormat[K, V]],
  val keyClass: Class[K],
  val valueClass: Class[V],
  @transient conf: Configuration)
  extends RDD[(K, V)](sc.asInstanceOf[org.apache.spark.SparkContext], Nil)
  with Logging {

  this.name = path

  logInfo("Creating instance of TezRDD for path: " + path)

  override def toString = this.name

  /**
   *
   */
  override def getPartitions: Array[Partition] = {
    Array(new Partition {
      override def index: Int = 0
    })
  }
  /**
   *
   */
  override def compute(theSplit: Partition, context: TaskContext): InterruptibleIterator[(K, V)] = {
    val iterator = SparkEnv.get.shuffleManager.getReader(null, 0, 0, null).read.asInstanceOf[Iterator[(K, V)]]
    new InterruptibleIterator(new TaskContext(0, 1, 1, true), iterator)
  }
}

/**
 * 
 */
class PostProcessFunction[K, V] extends Function2[TaskContext, Iterator[_ <: Product2[K, V]], Any] with Serializable {
  def apply(v1: TaskContext, iter: Iterator[_ <: Product2[K, V]]): Any = {
    val writer = SparkEnv.get.shuffleManager.getWriter[K, V](null, 0, null)
    writer.write(iter)
  }
}
