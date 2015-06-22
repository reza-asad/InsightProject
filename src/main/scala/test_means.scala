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

//package org.apache.spark.examples.mllib

import kafka.serializer.StringDecoder
import org.apache.spark.SparkConf
import org.apache.spark.mllib.clustering.StreamingKMeans
import org.apache.spark.streaming.kafka._
import org.apache.spark.streaming._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.elasticsearch.spark._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._
import com.codahale.jerkson.Json
import org.apache.spark.streaming.StreamingContext._
import scala.concurrent.Await
import scala.concurrent.duration._
import wabisabi._
import scala.concurrent.ExecutionContext.Implicits.global



/**
 * Estimate clusters on one stream of data and make predictions
 * on another stream, where the data streams arrive as text files
 * into two different directories.
 *
 * The rows of the training text files must be vector data in the form
 * `[x1,x2,x3,...,xn]`
 * Where n is the number of dimensions.
 *
 * The rows of the test text files must be labeled data in the form
 * `(y,[x1,x2,x3,...,xn])`
 * Where y is some identifier. n must be the same for train and test.
 *
 * Usage:
 *   StreamingKMeansExample <trainingDir> <testDir> <batchDuration> <numClusters> <numDimensions>
 *
 * To run on your local machine using the two directories `trainingDir` and `testDir`,
 * with updates every 5 seconds, 2 dimensions per data point, and 3 clusters, call:
 *    $ bin/run-example mllib.StreamingKMeansExample trainingDir testDir 5 3 2
 *
 * As you add text files to `trainingDir` the clusters will continuously update.
 * Anytime you add text files to `testDir`, you'll see predicted labels using the current model.
 *
 */
object test_means{

  def main(args: Array[String]) {
  
    val brokers = "ec2-52-8-179-244.us-west-1.compute.amazonaws.com:9092"
    val topics = "my-topic"
    val topicsSet = topics.split(",").toSet
    val client = new Client("http://ec2-52-8-179-244.us-west-1.compute.amazonaws.com:9200")
	var counter = 0
	
    // Create context with 2 second batch interval
    val sparkConf = new SparkConf().setAppName("test_means")
  	sparkConf.set("es.index.auto.create", "true")
  	sparkConf.set("es.nodes", "ec2-52-8-179-244.us-west-1.compute.amazonaws.com:9200") 
  	sparkConf.set("spark.executor.memory", "1g")
    val ssc = new StreamingContext(sparkConf, Seconds(5.toLong))
    
    // Create direct kafka stream with brokers and topics
	val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)
	val messages = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topicsSet).map(_._2)
	val points = messages.map(x => parse(x))

	// Create Training and Test Data
	val trainingData = points.map(x => Vectors.sparse(2,Seq((0,compact(render(x \ "location" \ "coordinate" \ "latitude")).toDouble),
								   		 					(1,compact(render(x \ "location" \ "coordinate" \"longitude")).toDouble))))	

	val testData = 	points.map(x => LabeledPoint(compact(render(x \ "id")).toInt, Vectors.sparse(2,Seq(
														(0,compact(render(x \ "location" \ "coordinate" \ "latitude")).toDouble),
								   		 				(1,compact(render(x \ "location" \ "coordinate" \"longitude")).toDouble)))))


    
    val model = new StreamingKMeans()
      .setK(100000.toInt)
      .setDecayFactor(0.1)
      .setRandomCenters(2.toInt, 0.0)
    model.trainOn(trainingData)
  
	
	val prediction = model.predictOnValues(testData.map(lp => (lp.label, lp.features)))
	val tupleData = points.map(x => (compact(render(x \ "id")).toDouble,
									(compact(render(x \ "location" \ "coordinate" \ "latitude")).toDouble,
									 compact(render(x \ "location" \ "coordinate" \"longitude")).toDouble)))
	
	val data = tupleData.join(prediction)
	val output = data.map(x => Map("name"-> x._1.toInt,
								   "cluster"->x._2._2,
								   "location"-> Map("lat"->x._2._1._1, "lon"->x._2._1._2)))

	output.foreachRDD { rdd => {
//		rdd.collect().foreach(t => println(t))
		rdd.saveToEs("simple/people")
		}
	}

    
    ssc.start()
    ssc.awaitTermination()
  }
}