package org.apache.spark.tez;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.tez.client.TezClient;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.DataSinkDescriptor;
import org.apache.tez.dag.api.DataSourceDescriptor;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.UserPayload;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.input.MRInput;
import org.apache.tez.mapreduce.output.MROutput;
import org.apache.tez.runtime.library.conf.OrderedPartitionedKVEdgeConfig;
import org.apache.tez.runtime.library.partitioner.HashPartitioner;

import com.hortonworks.spark.tez.TezWordCount.SumProcessor;
import com.hortonworks.spark.tez.TezWordCount.TokenProcessor;
import com.hortonworks.spark.tez.processor.TezSparkProcessor;

public class DAGBuilder {
	
	private final Log logger = LogFactory.getLog(DAGBuilder.class);
	
	private final Configuration tezConfiguration;
	
	private final Map<Integer, VertexDescriptor> vertexes = new LinkedHashMap<Integer, VertexDescriptor>();
	
	private final TezClient tezClient;
	
//	private final FileSystem fileSystem;
	
//	private final String user;
	
//	private final Path stagingDir;
	
	private final Map<String, LocalResource> localResources;
	
	private final String outputPath;
	
	private final String applicationInstanceName;

	private DAG dag;
	
	public DAGBuilder(TezClient tezClient, Map<String, LocalResource> localResources, Configuration tezConfiguration, String outputPath) {
		this.tezClient = tezClient;
		this.tezConfiguration = tezConfiguration;
		this.applicationInstanceName = tezClient.getClientName() + "_" + System.currentTimeMillis();
		this.dag = new DAG(this.applicationInstanceName);
		this.outputPath = outputPath;
		this.localResources = localResources;
	}
	/**
	 * 
	 * @return
	 */
	public DAGTask build(){
		try {
			this.doBuild();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return new DAGTask(){
			@Override
			public void execute() {
				try {
					DAGBuilder.this.run();
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try {
						tezClient.stop();
					} catch (Exception e2) {
						// ignore
					}
				}
			}
		};
	}
	
	/**
	 * 
	 */
	private void run(){
	    try { 	
		    DAGClient dagClient = null;
//	        if (this.fileSystem.exists(new Path(outputPath))) {
//	          throw new FileAlreadyExistsException("Output directory " + this.outputPath + " already exists");
//	        }

		    logger.info("Before ready");
	        tezClient.waitTillReady();
	        logger.info("After ready");
	        
	        System.out.println("EXECUTED DAG");
	        dagClient = tezClient.submitDAG(this.dag);

	        // monitoring
	        logger.info("Before status");
	        DAGStatus dagStatus =  dagClient.waitForCompletionWithStatusUpdates(null);
	        
	        if (dagStatus.getState() != DAGStatus.State.SUCCEEDED) {
	          logger.error("DAG diagnostics: " + dagStatus.getDiagnostics());
	        }
	        logger.info("After status");
	    } catch (Exception e) {
	    	throw new IllegalStateException("Failed to execute DAG", e);
	    } 
	}

	/**
	 * 
	 * @param vd
	 */
	public void addVertex(VertexDescriptor vd) {
		vertexes.put(vd.getStageId(), vd);
	}
	
	/**
	 * 
	 */
	public String toString() {
		return vertexes.toString();
	}
	/**
	 * 
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	private void doBuild() throws Exception {
		logger.debug("Building Tez DAG");
	
		OrderedPartitionedKVEdgeConfig edgeConf = OrderedPartitionedKVEdgeConfig
		        .newBuilder(Text.class.getName(), IntWritable.class.getName(),
		            HashPartitioner.class.getName(), null).build();
		
		int sequenceCounter = 0;
		int counter = 0;
		for (Entry<Integer, VertexDescriptor> vertexDescriptorEntry : vertexes.entrySet()) {
			counter++;
			VertexDescriptor vertexDescriptor = vertexDescriptorEntry.getValue();
			
			if (vertexDescriptor.getInput() instanceof TezRDD) {
				String inputPath = ((TezRDD<?,?>)vertexDescriptor.getInput()).name();
				DataSourceDescriptor dataSource = MRInput.createConfigBuilder(new Configuration(tezConfiguration), TextInputFormat.class, inputPath).build();
				
				UserPayload payload = UserPayload.create(vertexDescriptor.getSerTaskData());

				String vertexName = String.valueOf(sequenceCounter++);
				String dsName = String.valueOf(sequenceCounter++);
				Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(TezSparkProcessor.class.getName()).setUserPayload(payload)).addDataSource(dsName, dataSource);
//				Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(TokenProcessor.class.getName()).setUserPayload(payload)).addDataSource(dsName, dataSource);

				vertex.setTaskLocalFiles(localResources);
				dag.addVertex(vertex);
			}
			else {
				if (counter == vertexes.size()) {
					DataSinkDescriptor dataSink = MROutput.createConfigBuilder(new Configuration(tezConfiguration), TextOutputFormat.class, outputPath).build();
					UserPayload payload = UserPayload.create(vertexDescriptor.getSerTaskData());
					String vertexName = String.valueOf(sequenceCounter++);
					String dsName = String.valueOf(sequenceCounter++);
					Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(TezSparkProcessor.class.getName()).setUserPayload(payload), vertexDescriptor.getNumPartitions()).addDataSink(dsName, dataSink);
//					Vertex vertex = Vertex.create(vertexName, ProcessorDescriptor.create(SumProcessor.class.getName()).setUserPayload(payload), vertexDescriptor.getNumPartitions()).addDataSink(dsName, dataSink);
					vertex.setTaskLocalFiles(localResources);
					dag.addVertex(vertex);
					
				    if (!(vertexDescriptor.getInput() instanceof String)) {
				    	for (int stageId : (Iterable<Integer>)vertexDescriptor.getInput()) {
				    		VertexDescriptor vd = vertexes.get(stageId);
					    	String vName =  vd.getVertexId() * 2 + "";
					    	Vertex v = dag.getVertex(vName);
					    	Edge edge = Edge.create(v, vertex, edgeConf.createDefaultEdgeProperty());
					    	this.dag.addEdge(edge);
						}
				    }    
				}
				else {
					ProcessorDescriptor pd = ProcessorDescriptor.create(TezSparkProcessor.class.getName());
					UserPayload payload = UserPayload.create(vertexDescriptor.getSerTaskData());
					pd.setUserPayload(payload);
					Vertex vertex = Vertex.create(String.valueOf(sequenceCounter++), pd, vertexDescriptor.getNumPartitions(), MRHelpers.getResourceForMRReducer(this.tezConfiguration));
					vertex.setTaskLocalFiles(localResources);
					
				    this.dag.addVertex(vertex);
				    if (!(vertexDescriptor.getInput() instanceof String)) {
				    	for (int stageId : (Iterable<Integer>)vertexDescriptor.getInput()) {
				    		VertexDescriptor vd = vertexes.get(stageId);
					    	String vertexName =  vd.getVertexId() * 2 + "";
					    	Vertex v = dag.getVertex(vertexName);
					    	Edge edge = Edge.create(v, vertex, edgeConf.createDefaultEdgeProperty());
					    	this.dag.addEdge(edge);
						}
				    } 
				}
			}
		}
		logger.debug("Finished building Tez DAG");
	}
	
}
