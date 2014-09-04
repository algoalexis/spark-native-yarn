package dev.demo

import org.apache.spark.tez.instrument.TezInstrumentationAgent
import org.apache.spark.tez.TezConstants
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.tez.dag.api.TezConfiguration
import java.net.URLClassLoader
import java.net.URL
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 
 */
object WordCount extends BaseDemo {

  def main(args: Array[String]) {
    
    val jobName = "WordCount"
    val inputFile = "src/test/scala/dev/demo/test.txt"
    prepare(jobName, Array(inputFile))

    val sConf = new SparkConf
    sConf.setAppName(jobName)
    sConf.setMaster("local")
//    sConf.set("spark.shuffle.spill", "false")
    val sc = new SparkContext(sConf)
    val source = sc.textFile(inputFile)
    
    val result = source
    	.flatMap{x => 
    	  println("flatmap")
    	  x.split(" ")
    	}.map{x => 
    	  println("map")
    	  (x, 1)}.
    	  reduceByKey((x,y) => x+y, 2).saveAsTextFile("hello")
    	    

//    println(result.toList)

    sc.stop
  }

}