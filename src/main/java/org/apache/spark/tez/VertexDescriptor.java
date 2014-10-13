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
package org.apache.spark.tez;

import java.nio.ByteBuffer;

/**
 * Collector class which contains contains data required to build Tez Vertex
 */
public class VertexDescriptor {
	private final int stageId;
	private final int vertexId;
	private final Object input;
	private final ByteBuffer serTaskData;
	private Class<?> inputFormatClass;	
	private Class<?> key;
	private Class<?> value;
	private int numPartitions = 1; // must be set to the amount of reducers or 1. Must NEVER be 0 otherwise there will be ArithmeticException in partitioner
	
	/**
	 * 
	 * @param stageId
	 * @param vertexId
	 * @param input
	 * @param serTaskData
	 */
	public VertexDescriptor(int stageId, int vertexId, Object input, ByteBuffer serTaskData){
		this.stageId = stageId;
		this.vertexId = vertexId;
		this.input = input;
		this.serTaskData = serTaskData;
	}
	
	/**
	 * 
	 * @return
	 */
	public int getStageId() {
		return stageId;
	}

	/**
	 * 
	 * @return
	 */
	public int getVertexId() {
		return vertexId;
	}

	/**
	 * 
	 * @return
	 */
	public Object getInput() {
		return input;
	}

	/**
	 * 
	 * @return
	 */
	public ByteBuffer getSerTaskData() {
		return serTaskData;
	}
	
	/**
	 * 
	 * @return
	 */
	public int getNumPartitions() {
		return numPartitions;
	}
	
	/**
	 * 
	 */
	public String toString(){
		return "(stage: " + this.stageId + "; vertex:" + this.vertexId + "; input:" + input + ")";
	}
	
	/**
	 * 
	 * @param inputFormatClass
	 */
	public void setInputFormatClass(Class<?> inputFormatClass) {
		this.inputFormatClass = inputFormatClass;
	}

	/**
	 * 
	 * @param key
	 */
	public void setKey(Class<?> key) {
		this.key = key;
	}

	/**
	 * 
	 * @param value
	 */
	public void setValue(Class<?> value) {
		this.value = value;
	}
	
	/**
	 * 
	 * @param numPartitions
	 */
	public void setNumPartitions(int numPartitions) {
		if (numPartitions > 0){
			this.numPartitions = numPartitions;
		}
	}
}