
package application;


import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.net.InetAddress;
import java.util.ArrayList;

import org.apache.log4j.Logger;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;

/**
 * @author Peter Rasmussen
 * <p>
 * Class for an operator that receives a tuples stores them in a list and then sends them to ElasticSearch. 
 * <P>
 * The following event methods from the Operator interface can be called:
 * </p>
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to process and submit tuples</li> 
 * <li>process() handles a tuple arriving on an input port 
 * <li>processPuncuation() handles a punctuation mark arriving on an input port 
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any time, 
 * such as a request to stop a PE or cancel a job. 
 * Thus the shutdown() may occur while the operator is processing tuples, punctuation marks, 
 * or even during port ready notification.</li>
 * </ul>
 * <p>With the exception of operator initialization, all the other events may occur concurrently with each other, 
 * which lead to these methods being called concurrently by different threads.</p> 
 */
@PrimitiveOperator(name="ElastikSearchSink", namespace="application",
description="Java Operator ElastikSearchSink")
@InputPorts({@InputPortSet(description="Port that ingests tuples", cardinality=1, optional=false, windowingMode=WindowMode.NonWindowed, windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious), @InputPortSet(description="Optional input ports", optional=true, windowingMode=WindowMode.NonWindowed, windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious)})
@Libraries({"impl/java/lib/*"})
public class ElastikSearchSink extends AbstractOperator {
	private String elasticHost = null;
	private Client client = null;
	private String clusterName = "elasticsearch";
	private ArrayList<XContentBuilder>documents = new ArrayList<>();
	private long bulkSize = 5000;
	private String indexName = null;
	private String dataType = null;
	boolean sendOnPunct = true;
	
    /**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        Settings settings = Settings.settingsBuilder().put("cluster.name", clusterName).build();   
        client = TransportClient.builder().settings(settings).build().addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(elasticHost), 9300));
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " ElasticSearch Client created - Job: " + context.getPE().getJobId() );
	}

    /**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
    	// This method is commonly used by source operators. 
    	// Operators that process incoming tuples generally do not need this notification. 
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
    }

    /**
     * Process an incoming tuple that arrived on the specified port.
     * <P>
     * Copy the incoming tuple to a new output tuple and submit to the output port. 
     * </P>
     * @param inputStream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public final void process(StreamingInput<Tuple> inputStream, Tuple tuple) throws Exception {

		XContentBuilder json = jsonBuilder().startObject();
		
		for (Attribute attribute : tuple.getStreamSchema()) {
			 json.field(attribute.getName(), tuple.getString(attribute.getName()));
		}
		
		json.endObject();
		synchronized(documents){
			documents.add(json);
			 
			if (documents.size()>bulkSize) {
				System.out.println("Sending data");
				BulkRequestBuilder bulkRequest = client.prepareBulk();
				
				IndexRequestBuilder irb = client.prepareIndex(indexName, dataType);
				for(XContentBuilder jsonDoc:documents){
					irb.setSource(jsonDoc);
					bulkRequest.add(irb);
				}
		       
				BulkResponse bulkResponse = bulkRequest.get();
				if (bulkResponse.hasFailures()) {
				    // process failures by iterating through each bulk response item	 
					for (BulkItemResponse bir: bulkResponse.getItems()){				
						Logger.getLogger(this.getClass()).trace("Failed to send items to elastic search : " + bir.getFailureMessage());
					}
				}
				documents.clear();
			}
		}
	}

    
    @Parameter(name="elasticHost", optional=false)
    public void setId(String elasticHost) {
        this.elasticHost = elasticHost;
    }

    @Parameter(name="clusterName", optional=true)
    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }
    
    @Parameter(name="indexName", optional=false)
    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }
    
    @Parameter(name="dataType", optional=false)
    public void setDataType(String dataType) {
        this.dataType = dataType;
    }
    
    @Parameter(name="bulkSize", optional=true)
    public void setBulkSize(long bulkSize) {
        this.bulkSize = bulkSize;
    }
    
    @Parameter(name="sendOnPunct", optional=true)
    public void setSendOnPunct(boolean sendOnPunct) {
        this.sendOnPunct = sendOnPunct;
    }
    
    /**
     * Process an incoming punctuation that arrived on the specified port.
     * @param stream Port the punctuation is arriving on.
     * @param mark The punctuation mark
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void processPunctuation(StreamingInput<Tuple> stream, Punctuation mark) throws Exception {
    	// For window markers, punctuate all output ports 
    	super.processPunctuation(stream, mark);
    	
    	synchronized(documents){
	    	if (documents.size()>0) {
				System.out.println("Sending data");
				BulkRequestBuilder bulkRequest = client.prepareBulk();
				
				IndexRequestBuilder irb = client.prepareIndex(indexName, dataType);
				for(XContentBuilder jsonDoc:documents){
					irb.setSource(jsonDoc);
					bulkRequest.add(irb);
				}
		       
				BulkResponse bulkResponse = bulkRequest.get();
				if (bulkResponse.hasFailures()) {
					// process failures by iterating through each bulk response item	 
					for (BulkItemResponse bir: bulkResponse.getItems()){				
						Logger.getLogger(this.getClass()).trace("Failed to send items to elastic search : " + bir.getFailureMessage());
					}
				}
				documents.clear();
			}
    	}
    }

    /**
     * Shutdown this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        // TODO: If needed, close connections or release resources related to any external system or data store.
        if(client!=null){
        	client.close();
        }
        // Must call super.shutdown()
        super.shutdown();
    }
}
