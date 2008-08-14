/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import java.io.File;
import java.util.Arrays;
import java.util.Vector;

import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKSK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileUtil;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

/**
 * @author amphibian
 */
public class RealNodeRequestInsertTest extends RealNodeRoutingTest {

    static final int NUMBER_OF_NODES = 100;
    static final int DEGREE = 10;
    static final short MAX_HTL = (short)10;
    static final boolean START_WITH_IDEAL_LOCATIONS = true;
    static final boolean FORCE_NEIGHBOUR_CONNECTIONS = true;
    static final boolean ENABLE_SWAPPING = false;
    static final boolean ENABLE_ULPRS = false;
    static final boolean ENABLE_PER_NODE_FAILURE_TABLES = false;
    static final boolean ENABLE_SWAP_QUEUEING = false;
    static final boolean ENABLE_PACKET_COALESCING = true;
    static final boolean ENABLE_FOAF = true;
    
    static final int TARGET_SUCCESSES = 20;
    //static final int NUMBER_OF_NODES = 50;
    //static final short MAX_HTL = 10;
    
    public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException {
        String name = "realNodeRequestInsertTest";
        File wd = new File(name);
        if(!FileUtil.removeAll(wd)) {
        	System.err.println("Mass delete failed, test may not be accurate.");
        	System.exit(EXIT_CANNOT_DELETE_OLD_DATA);
        }
        wd.mkdir();
    	freenet.node.RequestHandler.SEND_OLD_FORMAT_SSK = false;
        //NOTE: globalTestInit returns in ignored random source
        //NodeStarter.globalTestInit(name, false, Logger.ERROR, "freenet.node.Location:normal,freenet.node.simulator.RealNode:minor,freenet.node.Insert:MINOR,freenet.node.Request:MINOR,freenet.node.Node:MINOR");
        //NodeStarter.globalTestInit(name, false, Logger.ERROR, "freenet.node.Location:MINOR,freenet.io.comm:MINOR,freenet.node.NodeDispatcher:MINOR,freenet.node.simulator:MINOR,freenet.node.PeerManager:MINOR,freenet.node.RequestSender:MINOR");
        //NodeStarter.globalTestInit(name, false, Logger.ERROR, "freenet.node.FNP:MINOR,freenet.node.Packet:MINOR,freenet.io.comm:MINOR,freenet.node.PeerNode:MINOR,freenet.node.DarknetPeerNode:MINOR");
        NodeStarter.globalTestInit(name, false, Logger.ERROR, "");
        System.out.println("Insert/retrieve test");
        System.out.println();
        DummyRandomSource random = new DummyRandomSource();
        //DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTest.class, "Creating nodes...");
        Executor executor = new PooledExecutor();
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i] = 
            	NodeStarter.createTestNode(5001+i, 0, name, false, true, false, MAX_HTL, 20 /* 5% */, random, executor, 500*NUMBER_OF_NODES, 256*1024, true, ENABLE_SWAPPING, false, ENABLE_ULPRS, ENABLE_PER_NODE_FAILURE_TABLES, ENABLE_SWAP_QUEUEING, ENABLE_PACKET_COALESCING, 12000, ENABLE_FOAF, false);
            Logger.normal(RealNodeRoutingTest.class, "Created node "+i);
        }
        
        // Now link them up
        makeKleinbergNetwork(nodes, START_WITH_IDEAL_LOCATIONS, DEGREE, FORCE_NEIGHBOUR_CONNECTIONS);

        Logger.normal(RealNodeRoutingTest.class, "Added random links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i].start(false);
            System.err.println("Started node "+i+"/"+nodes.length);
        }
        
        waitForAllConnected(nodes);
        
        waitForPingAverage(0.95, nodes, random, MAX_PINGS, 1000);
        
        System.out.println();
        System.out.println("Ping average > 95%, lets do some inserts/requests");
        System.out.println();
        int requestNumber = 0;
        RunningAverage requestsAvg = new SimpleRunningAverage(100, 0.0);
        String baseString = System.currentTimeMillis() + " ";
		int insertAttempts = 0;
		int insertSuccesses = 0;
		int fetchSuccesses = 0;
        while(true) {
            try {
                requestNumber++;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e1) {
                }
                String dataString = baseString + requestNumber;
                // Pick random node to insert to
                int node1 = random.nextInt(NUMBER_OF_NODES);
                Node randomNode = nodes[node1];
                //Logger.error(RealNodeRequestInsertTest.class,"Inserting: \""+dataString+"\" to "+node1);
                
                //boolean isSSK = requestNumber % 2 == 1;
                boolean isSSK = true;
                
                FreenetURI testKey;
                ClientKey insertKey;
                ClientKey fetchKey;
                ClientKeyBlock block;
                
            	byte[] buf = dataString.getBytes("UTF-8");
                if(isSSK) {
                	testKey = new FreenetURI("KSK", dataString);
                	
                	insertKey = InsertableClientSSK.create(testKey);
                	fetchKey = ClientKSK.create(testKey);
                	
                	block = ((InsertableClientSSK)insertKey).encode(new ArrayBucket(buf), false, false, (short)-1, buf.length, random);
                } else {
                	block = ClientCHKBlock.encode(buf, false, false, (short)-1, buf.length);
                	insertKey = fetchKey = block.getClientKey();
                	testKey = insertKey.getURI();
                }
                
                System.err.println();
                System.err.println("Created random test key "+testKey+" = "+fetchKey.getNodeKey());
                System.err.println();
                
                byte[] data = dataString.getBytes("UTF-8");
                Logger.minor(RealNodeRequestInsertTest.class, "Decoded: "+new String(block.memoryDecode()));
                Logger.normal(RealNodeRequestInsertTest.class,"Insert Key: "+insertKey.getURI());
                Logger.normal(RealNodeRequestInsertTest.class,"Fetch Key: "+fetchKey.getURI());
				try {
					insertAttempts++;
					randomNode.clientCore.realPut(block, true);
					Logger.error(RealNodeRequestInsertTest.class, "Inserted to "+node1);
					insertSuccesses++;
				} catch (freenet.node.LowLevelPutException putEx) {
					Logger.error(RealNodeRequestInsertTest.class, "Insert failed: "+ putEx);
					System.err.println("Insert failed: "+ putEx);
					System.exit(EXIT_INSERT_FAILED);
				}
                // Pick random node to request from
                int node2;
                do {
                    node2 = random.nextInt(NUMBER_OF_NODES);
                } while(node2 == node1);
                Node fetchNode = nodes[node2];
                block = (ClientKeyBlock) fetchNode.clientCore.realGetKey(fetchKey, false, true, false);
                if(block == null) {
					int percentSuccess=100*fetchSuccesses/insertAttempts;
                    Logger.error(RealNodeRequestInsertTest.class, "Fetch #"+requestNumber+" FAILED ("+percentSuccess+"%); from "+node2);
                    requestsAvg.report(0.0);
                    System.exit(EXIT_REQUEST_FAILED);
                } else {
                    byte[] results = block.memoryDecode();
                    requestsAvg.report(1.0);
                    if(Arrays.equals(results, data)) {
						fetchSuccesses++;
						int percentSuccess=100*fetchSuccesses/insertAttempts;
                        Logger.error(RealNodeRequestInsertTest.class, "Fetch #"+requestNumber+" from node "+node2+" succeeded ("+percentSuccess+"%): "+new String(results));
                        System.err.println("Fetch #"+requestNumber+" succeeded ("+percentSuccess+"%): \""+new String(results)+'\"');
                        if(fetchSuccesses == TARGET_SUCCESSES) {
                        	System.err.println("Succeeded, "+TARGET_SUCCESSES+" successful fetches");
                        	System.exit(0);
                        }
                    } else {
                        Logger.error(RealNodeRequestInsertTest.class, "Returned invalid data!: "+new String(results));
                        System.err.println("Returned invalid data!: "+new String(results));
                        System.exit(EXIT_BAD_DATA);
                    }
                }
                StringBuffer load = new StringBuffer("Running UIDs for nodes: ");
                int totalRunningUIDs = 0;
                int totalRunningUIDsAlt = 0;
                Vector runningUIDsList = new Vector(); // <Long>
                for(int i=0;i<nodes.length;i++) {
                	load.append(i);
                	load.append(':');
                	nodes[i].addRunningUIDs(runningUIDsList);
                	int runningUIDs = nodes[i].getTotalRunningUIDs();
                	int runningUIDsAlt = nodes[i].getTotalRunningUIDsAlt();
                	totalRunningUIDs += runningUIDs;
                	totalRunningUIDsAlt += runningUIDsAlt;
                	load.append(totalRunningUIDs);
                	load.append(':');
                	load.append(totalRunningUIDsAlt);
                	if(i != nodes.length-1)
                		load.append(' ');
                }
                System.err.println(load.toString());
                if(totalRunningUIDs != 0)
                	System.err.println("Still running UIDs: "+totalRunningUIDs);
                if(totalRunningUIDsAlt != 0)
                	System.err.println("Still running UIDs (alt): "+totalRunningUIDsAlt);
                if(!runningUIDsList.isEmpty()) {
                	System.err.println("List of running UIDs: "+Arrays.toString(runningUIDsList.toArray()));
                }
            } catch (Throwable t) {
                Logger.error(RealNodeRequestInsertTest.class, "Caught "+t, t);
            }
        }
    }
}
