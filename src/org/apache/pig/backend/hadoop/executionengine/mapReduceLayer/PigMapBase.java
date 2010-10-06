/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.mapReduceLayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.TargetedTuple;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.io.PigNullableWritable;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.SpillableMemoryManager;

public abstract class PigMapBase extends MapReduceBase{
    private static final Tuple DUMMYTUPLE = null;

    private final Log log = LogFactory.getLog(getClass());
    
    protected byte keyType;
    
    
    //Map Plan
    protected PhysicalPlan mp;
    protected TupleFactory tf = TupleFactory.getInstance();
    
    OutputCollector<PigNullableWritable, Writable> outputCollector;
    
    // Reporter that will be used by operators
    // to transmit heartbeat
    ProgressableReporter pigReporter;

    protected boolean errorInMap = false;
    
    PhysicalOperator[] roots;

    private PhysicalOperator leaf;

    private boolean initialized = false;
    
    /**
     * Will be called when all the tuples in the input
     * are done. So reporter thread should be closed.
     */
    @Override
    public void close() throws IOException {
        super.close();
        PhysicalOperator.setReporter(null);
        if(errorInMap) {
            //error in map - returning
            return;
        }
            
        if(PigMapReduce.sJobConf.get("pig.stream.in.map", "false").equals("true")) {
            // If there is a stream in the pipeline we could 
            // potentially have more to process - so lets
            // set the flag stating that all map input has been sent
            // already and then lets run the pipeline one more time
            // This will result in nothing happening in the case
            // where there is no stream in the pipeline
            mp.endOfAllInput = true;
            try {
                runPipeline(leaf);
            } catch (ExecException e) {
                 IOException ioe = new IOException("Error running pipeline in close() of map");
                 ioe.initCause(e);
                 throw ioe;
            }
        }
        mp = null;
        
    }

    /**
     * Configures the mapper with the map plan and the
     * reproter thread
     */
    @Override
    public void configure(JobConf job) {
        super.configure(job);
        SpillableMemoryManager.configure(ConfigurationUtil.toProperties(job));
        PigMapReduce.sJobConf = job;
        try {
            mp = (PhysicalPlan) ObjectSerializer.deserialize(job
                    .get("pig.mapPlan"));
            
            // To be removed
            if(mp.isEmpty())
                log.debug("Map Plan empty!");
            else{
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                mp.explain(baos);
                log.debug(baos.toString());
            }
            keyType = ((byte[])ObjectSerializer.deserialize(job.get("pig.map.keytype")))[0];
            // till here
            
            long sleepTime = job.getLong("pig.reporter.sleep.time", 10000);
            
            pigReporter = new ProgressableReporter();
            if(!(mp.isEmpty())) {
                List<OperatorKey> targetOpKeys = 
                    (ArrayList<OperatorKey>)ObjectSerializer.deserialize(job.get("map.target.ops"));
                ArrayList<PhysicalOperator> targetOpsAsList = new ArrayList<PhysicalOperator>();
                for (OperatorKey targetKey : targetOpKeys) {                    
                    targetOpsAsList.add(mp.getOperator(targetKey));
                }
                roots = targetOpsAsList.toArray(new PhysicalOperator[1]);
                leaf = mp.getLeaves().get(0);
            }
        } catch (IOException e) {
            log.error(e.getMessage() + "was caused by:");
            log.error(e.getCause().getMessage());
        }
    }
    
    /**
     * The map function that attaches the inpTuple appropriately
     * and executes the map plan if its not empty. Collects the
     * result of execution into oc or the input directly to oc
     * if map plan empty. The collection is left abstract for the
     * map-only or map-reduce job to implement. Map-only collects
     * the tuple as-is whereas map-reduce collects it after extracting
     * the key and indexed tuple.
     */
    public void map(Text key, Tuple inpTuple,
            OutputCollector<PigNullableWritable, Writable> oc,
            Reporter reporter) throws IOException {
        
        if(!initialized) {
            initialized  = true;
            // cache the collector for use in runPipeline() which
            // can be called from close()
            this.outputCollector = oc;
            pigReporter.setRep(reporter);
            PhysicalOperator.setReporter(pigReporter);
        }
        
        if(mp.isEmpty()){
            try{
                collect(oc,inpTuple);
            } catch (ExecException e) {
                IOException ioe = new IOException(e.getMessage());
                ioe.initCause(e.getCause());
                throw ioe;
            }
            return;
        }
        
        for (PhysicalOperator root : roots) {
            root.attachInput(inpTuple);
        }
        try {
            runPipeline(leaf);
            
        } catch (ExecException e) {
            IOException ioe = new IOException(e.getMessage());
            ioe.initCause(e.getCause());
            throw ioe;
        }
    }

    private void runPipeline(PhysicalOperator leaf) throws IOException, ExecException {
        while(true){
            Result res = leaf.getNext(DUMMYTUPLE);
            if(res.returnStatus==POStatus.STATUS_OK){
                collect(outputCollector,(Tuple)res.result);
                continue;
            }
            
            if(res.returnStatus==POStatus.STATUS_EOP) {
                return;
            }
            
            if(res.returnStatus==POStatus.STATUS_NULL)
                continue;
            
            if(res.returnStatus==POStatus.STATUS_ERR){
                // remember that we had an issue so that in 
                // close() we can do the right thing
                errorInMap  = true;
                // if there is an errmessage use it
                String errMsg;
                if(res.result != null) {
                    errMsg = "Received Error while " +
                    "processing the map plan: " + res.result;
                } else {
                    errMsg = "Received Error while " +
                    "processing the map plan.";
                }
                    
                IOException ioe = new IOException(errMsg);
                throw ioe;
            }
        }
        
    }

    abstract public void collect(OutputCollector<PigNullableWritable, Writable> oc, Tuple tuple) throws ExecException, IOException;

    /**
     * @return the keyType
     */
    public byte getKeyType() {
        return keyType;
    }

    /**
     * @param keyType the keyType to set
     */
    public void setKeyType(byte keyType) {
        this.keyType = keyType;
    }
    
}