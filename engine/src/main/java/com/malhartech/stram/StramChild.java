/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.stram;

import com.malhartech.dag.BackupAgent;
import com.malhartech.dag.Component;
import com.malhartech.dag.ComponentContextPair;
import com.malhartech.dag.Node;
import com.malhartech.dag.NodeConfiguration;
import com.malhartech.dag.NodeContext;
import com.malhartech.dag.NodeSerDe;
import com.malhartech.dag.Sink;
import com.malhartech.dag.Stream;
import com.malhartech.dag.StreamConfiguration;
import com.malhartech.dag.StreamContext;
import com.malhartech.dag.WindowIdActivatedSink;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeat;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StramToNodeRequest;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingContainerContext;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingNodeHeartbeat;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingNodeHeartbeat.DNodeState;
import com.malhartech.stream.BufferServerInputStream;
import com.malhartech.stream.BufferServerOutputStream;
import com.malhartech.stream.InlineStream;
import com.malhartech.stream.MuxStream;
import com.malhartech.stream.PartitionAwareSink;
import com.malhartech.stream.SocketInputStream;
import com.malhartech.util.ScheduledThreadPoolExecutor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * The main() for streaming node processes launched by {@link com.malhartech.stram.StramAppMaster}.<p>
 * <br>
 *
 */
public class StramChild
{
  private static final Logger logger = LoggerFactory.getLogger(StramChild.class);
  private static final String NODE_PORT_SPLIT_SEPARATOR = "\\.";
  private static final String NODE_PORT_CONCAT_SEPARATOR = ".";
  //private static final String SOURCE_SINK_SEPARATOR = "\u279C";
  private final String containerId;
  private final Configuration conf;
  private final StreamingNodeUmbilicalProtocol umbilical;
  protected final Map<String, ComponentContextPair<Node, NodeContext>> nodes = new ConcurrentHashMap<String, ComponentContextPair<Node, NodeContext>>();
  private final Map<String, ComponentContextPair<Stream, StreamContext>> streams = new ConcurrentHashMap<String, ComponentContextPair<Stream, StreamContext>>();
  protected final Map<String, WindowGenerator> generators = new ConcurrentHashMap<String, WindowGenerator>();
  /**
   * for the following 3 fields, my preferred type is HashSet but synchronizing access to HashSet object was resulting in very verbose code.
   */
  private final Map<Node, NodeContext> activeNodes = new ConcurrentHashMap<Node, NodeContext>();
  private final Map<Stream, StreamContext> activeStreams = new ConcurrentHashMap<Stream, StreamContext>();
  private final Map<WindowGenerator, Object> activeGenerators = new ConcurrentHashMap<WindowGenerator, Object>();
  private long heartbeatIntervalMillis = 1000;
  private volatile boolean exitHeartbeatLoop = false;
  private final Object heartbeatTrigger = new Object();
  private String checkpointDfsPath;
  /**
   * Map of last backup window id that is used to communicate checkpoint state back to Stram. TODO: Consider adding this to the node context instead.
   */
  private final Map<String, Long> backupInfo = new ConcurrentHashMap<String, Long>();
  private long firstWindowMillis;
  private int windowWidthMillis;

  protected StramChild(String containerId, Configuration conf, StreamingNodeUmbilicalProtocol umbilical)
  {
    logger.debug("instantiated StramChild {}", containerId);
    this.umbilical = umbilical;
    this.containerId = containerId;
    this.conf = conf;
  }

  public void setup(StreamingContainerContext ctx) throws IOException
  {
    heartbeatIntervalMillis = ctx.getHeartbeatIntervalMillis();
    if (heartbeatIntervalMillis == 0) {
      heartbeatIntervalMillis = 1000;
    }

    firstWindowMillis = ctx.getStartWindowMillis();
    windowWidthMillis = ctx.getWindowSizeMillis();
    if (windowWidthMillis == 0) {
      windowWidthMillis = 500;
    }

    if ((this.checkpointDfsPath = ctx.getCheckpointDfsPath()) == null) {
      this.checkpointDfsPath = "checkpoint-dfs-path-not-configured";
    }

    deploy(ctx.nodeList);
  }

  public String getContainerId()
  {
    return this.containerId;
  }

  protected Map<String, ComponentContextPair<Node, NodeContext>> getNodes()
  {
    return this.nodes;
  }

  /**
   * Initialize container with nodes and streams in the context.
   * Existing nodes are not affected by this operation.
   *
   * @param ctx
   * @throws IOException
   */
  public static void main(String[] args) throws Throwable
  {
    logger.debug("Child starting with classpath: {}", System.getProperty("java.class.path"));

    final Configuration defaultConf = new Configuration();
    //defaultConf.addResource(MRJobConfig.JOB_CONF_FILE);
    UserGroupInformation.setConfiguration(defaultConf);

    String host = args[0];
    int port = Integer.parseInt(args[1]);
    final InetSocketAddress address =
            NetUtils.createSocketAddrForHost(host, port);

    final String childId = args[2];
    //Token<JobTokenIdentifier> jt = loadCredentials(defaultConf, address);

    // Communicate with parent as actual task owner.
    UserGroupInformation taskOwner =
            UserGroupInformation.createRemoteUser(StramChild.class.getName());
    //taskOwner.addToken(jt);
    final StreamingNodeUmbilicalProtocol umbilical =
            taskOwner.doAs(new PrivilegedExceptionAction<StreamingNodeUmbilicalProtocol>()
    {
      @Override
      public StreamingNodeUmbilicalProtocol run() throws Exception
      {
        return RPC.getProxy(StreamingNodeUmbilicalProtocol.class,
                            StreamingNodeUmbilicalProtocol.versionID, address, defaultConf);
      }
    });

    logger.debug("PID: " + System.getenv().get("JVM_PID"));
    UserGroupInformation childUGI;

    try {
      childUGI = UserGroupInformation.createRemoteUser(System.getenv(ApplicationConstants.Environment.USER.toString()));
      // Add tokens to new user so that it may execute its task correctly.
      for (Token<?> token: UserGroupInformation.getCurrentUser().getTokens()) {
        childUGI.addToken(token);
      }

      childUGI.doAs(new PrivilegedExceptionAction<Object>()
      {
        @Override
        public Object run() throws Exception
        {
          StramChild stramChild = new StramChild(childId, defaultConf, umbilical);
          StreamingContainerContext ctx = umbilical.getInitContext(childId);
          logger.debug("Got context: " + ctx);
          stramChild.setup(ctx);
          // main thread enters heartbeat loop
          stramChild.monitorHeartbeat();
          // teardown
          stramChild.deactivate();
          stramChild.teardown();
          return null;
        }
      });
    }
    catch (FSError e) {
      logger.error("FSError from child", e);
      umbilical.log(childId, e.getMessage());
    }
    catch (Exception exception) {
      logger.warn("Exception running child : "
              + StringUtils.stringifyException(exception));
      // Report back any failures, for diagnostic purposes
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      exception.printStackTrace(new PrintStream(baos));
      umbilical.log(childId, "FATAL: " + baos.toString());
    }
    catch (Throwable throwable) {
      logger.error("Error running child : "
              + StringUtils.stringifyException(throwable));
      Throwable tCause = throwable.getCause();
      String cause = tCause == null
                     ? throwable.getMessage()
                     : StringUtils.stringifyException(tCause);
      umbilical.log(childId, cause);
    }
    finally {
      RPC.stopProxy(umbilical);
      DefaultMetricsSystem.shutdown();
      // Shutting down log4j of the child-vm...
      // This assumes that on return from Task.activate()
      // there is no more logging done.
      LogManager.shutdown();
    }
  }

  public synchronized void deactivate()
  {
    for (WindowGenerator wg: activeGenerators.keySet()) {
      wg.deactivate();
    }
    activeGenerators.clear();

    for (Node node: activeNodes.keySet()) {
      node.deactivate();
    }

    for (Stream stream: activeStreams.keySet()) {
      stream.deactivate();
    }
    activeStreams.clear();
  }

  private synchronized void disconnectNode(String nodeid)
  {
    Node node = nodes.get(nodeid).component;
    disconnectWindowGenerator(nodeid, node);

    List<String> removableSocketOutputStreams = new ArrayList<String>();
    for (Entry<String, ComponentContextPair<Stream, StreamContext>> entry: streams.entrySet()) {
      String indexingKey = entry.getKey();
      Stream stream = entry.getValue().component;
      StreamContext context = entry.getValue().context;
      String sourceIdentifier = context.getSourceId();
      String sinkIdentifier = context.getSinkId();
      logger.debug("considering stream {} against id {}", stream, indexingKey);
      if (nodeid.equals(sourceIdentifier.split(NODE_PORT_SPLIT_SEPARATOR)[0])) {
        /**
         * the stream originates at the output port of one of the nodes that are going to vanish.
         */
        if (activeStreams.containsKey(stream)) {
          logger.debug("deactivating {}", stream);
          stream.deactivate();
          activeStreams.remove(stream);
        }
        removableSocketOutputStreams.add(sourceIdentifier);

        String[] sinkIds = sinkIdentifier.split(", ");
        for (String sinkId: sinkIds) {
          if (!sinkId.startsWith("tcp://")) {
            String[] nodeport = sinkId.split(NODE_PORT_SPLIT_SEPARATOR);
            ComponentContextPair<Node, NodeContext> npair = nodes.get(nodeport[0]);
            npair.component.connect(nodeport[1], null);
          }
          else if (stream.isMultiSinkCapable()) {
            ComponentContextPair<Stream, StreamContext> spair = streams.get(sinkId);
            logger.debug("found stream {} against {}", spair == null? null: spair.component, sinkId);
            if (spair == null) {
              assert(!sinkId.startsWith("tcp://"));
            }
            else {
              assert(sinkId.startsWith("tcp://"));
              if (activeStreams.containsKey(spair.component)) {
                logger.debug("deactivating {} for sink {}", spair.component, sinkId);
                spair.component.deactivate();
                activeStreams.remove(spair.component);
              }

              spair.component.connect(Component.INPUT, null);
              removableSocketOutputStreams.add(sinkId);
            }
          }
        }

        stream.connect(Component.INPUT, null);
      }
      else {
        /**
         * the stream may or may not feed into one of the nodes which are being undeployed.
         */
        String[] sinkIds = sinkIdentifier.split(", ");
        for (int i = sinkIds.length; i-- > 0;) {
          String[] nodeport = sinkIds[i].split(NODE_PORT_SPLIT_SEPARATOR);
          if (nodeid.equals(nodeport[0])) {
            stream.connect(sinkIds[i], null);
            node.connect(nodeport[1], null);
            sinkIds[i] = null;
          }
        }

        String sinkId = null;
        for (int i = sinkIds.length; i-- > 0;) {
          if (sinkIds[i] != null) {
            if (sinkId == null) {
              sinkId = sinkIds[i];
            }
            else {
              sinkId = sinkId.concat(", ").concat(sinkIds[i]);
            }
          }
        }

        if (sinkId == null) {
          if (activeStreams.containsKey(stream)) {
            logger.debug("deactivating {}", stream);
            stream.deactivate();
            activeStreams.remove(stream);
          }

          removableSocketOutputStreams.add(indexingKey);
        }
        else {
          // may be we should also check if the count has changed from something to 1
          // and replace mux with 1:1 sink. it's not necessary though.
          context.setSinkId(sinkId);
        }
      }
    }

    for (String streamId: removableSocketOutputStreams) {
      logger.debug("{} removing stream {}", Thread.currentThread(), streamId);
      ComponentContextPair<Stream, StreamContext> pair = streams.remove(streamId);
      pair.component.teardown();
    }
  }

  private void disconnectWindowGenerator(String nodeid, Node node)
  {
    WindowGenerator chosen1 = generators.remove(nodeid);
    if (chosen1 != null) {
      chosen1.connect(nodeid.concat(NODE_PORT_CONCAT_SEPARATOR).concat(Component.INPUT), null);
      node.connect(Component.INPUT, null);

      int count = 0;
      for (WindowGenerator wg: generators.values()) {
        if (chosen1 == wg) {
          count++;
        }
      }

      if (count == 0) {
        activeGenerators.remove(chosen1);
        chosen1.deactivate();
        chosen1.teardown();
      }
    }
  }

  private synchronized void undeploy(List<NodeDeployInfo> nodeList)
  {
    /**
     * make sure that all the nodes which we are asked to undeploy are in this container.
     */
    HashMap<String, Node> toUndeploy = new HashMap<String, Node>();
    for (NodeDeployInfo ndi: nodeList) {
      ComponentContextPair<Node, NodeContext> pair = nodes.get(ndi.id);
      if (pair == null) {
        throw new IllegalArgumentException("Node " + ndi.id + " is not hosted in this container!");
      }
      else if (toUndeploy.containsKey(ndi.id)) {
        throw new IllegalArgumentException("Node " + ndi.id + " is requested to be undeployed more than once");
      }
      else {
        toUndeploy.put(ndi.id, pair.component);
      }
    }

    for (NodeDeployInfo ndi: nodeList) {
      ComponentContextPair<Node, NodeContext> pair = nodes.get(ndi.id);
      if (activeNodes.containsKey(pair.component)) {
        pair.component.deactivate();
      }
    }
  }

  public void teardown()
  {
    HashSet<WindowGenerator> gens = new HashSet<WindowGenerator>();
    gens.addAll(generators.values());
    generators.clear();
    for (WindowGenerator wg: gens) {
      wg.teardown();
    }
    gens.clear();

    for (ComponentContextPair<Node, NodeContext> nc: this.nodes.values()) {
      nc.component.teardown();
    }

    for (Stream stream: activeStreams.keySet()) {
      stream.teardown();
    }
    activeStreams.clear();
  }

  protected void triggerHeartbeat()
  {
    synchronized (heartbeatTrigger) {
      heartbeatTrigger.notifyAll();
    }
  }

  protected void monitorHeartbeat() throws IOException
  {
    umbilical.log(containerId, "[" + containerId + "] Entering heartbeat loop..");
    logger.debug("Entering hearbeat loop (interval is {} ms)", this.heartbeatIntervalMillis);
    while (!exitHeartbeatLoop) {

      synchronized (this.heartbeatTrigger) {
        try {
          this.heartbeatTrigger.wait(heartbeatIntervalMillis);
        }
        catch (InterruptedException e1) {
          logger.warn("Interrupted in heartbeat loop, exiting..");
          break;
        }
      }

      long currentTime = System.currentTimeMillis();
      ContainerHeartbeat msg = new ContainerHeartbeat();
      msg.setContainerId(this.containerId);
      List<StreamingNodeHeartbeat> heartbeats = new ArrayList<StreamingNodeHeartbeat>(nodes.size());

      // gather heartbeat info for all nodes
      for (Map.Entry<String, ComponentContextPair<Node, NodeContext>> e: nodes.entrySet()) {
        StreamingNodeHeartbeat hb = new StreamingNodeHeartbeat();
        hb.setNodeId(e.getKey());
        hb.setGeneratedTms(currentTime);
        hb.setIntervalMs(heartbeatIntervalMillis);
        e.getValue().context.drainHeartbeatCounters(hb.getHeartbeatsContainer());
        hb.setState((activeNodes.containsKey(e.getValue().component)? DNodeState.PROCESSING: DNodeState.IDLE).toString());
        // propagate the backup window, if any
        Long backupWindowId = backupInfo.get(e.getKey());
        if (backupWindowId != null) {
          hb.setLastBackupWindowId(backupWindowId);
        }
        heartbeats.add(hb);
      }
      msg.setDnodeEntries(heartbeats);

      // heartbeat call and follow-up processing
      //logger.debug("Sending heartbeat for {} nodes.", msg.getDnodeEntries().size());
      try {
        ContainerHeartbeatResponse rsp = umbilical.processHeartbeat(msg);
        if (rsp != null) {
          processHeartbeatResponse(rsp);
          // keep polling at smaller interval if work is pending
          while (rsp != null && rsp.hasPendingRequests) {
            logger.info("Waiting for pending request.");
            synchronized (this.heartbeatTrigger) {
              try {
                this.heartbeatTrigger.wait(500);
              }
              catch (InterruptedException e1) {
                logger.warn("Interrupted in heartbeat loop, exiting..");
                break;
              }
            }
            rsp = umbilical.pollRequest(this.containerId);
            if (rsp != null) {
              processHeartbeatResponse(rsp);
            }
          }
        }
      }
      catch (Exception e) {
        logger.warn("Exception received (may be during shutdown?) {}", e.getLocalizedMessage(), e);
      }
    }
    logger.debug("Exiting hearbeat loop");
    umbilical.log(containerId, "[" + containerId + "] Exiting heartbeat loop..");
  }

  protected void processHeartbeatResponse(ContainerHeartbeatResponse rsp)
  {
    if (rsp.shutdown) {
      logger.info("Received shutdown request");
      this.exitHeartbeatLoop = true;
      return;
    }

    if (rsp.undeployRequest != null) {
      logger.info("Undeploy request: {}", rsp.undeployRequest);
      undeploy(rsp.undeployRequest);
    }

    if (rsp.deployRequest != null) {
      logger.info("Deploy request: {}", rsp.deployRequest);
      deploy(rsp.deployRequest);
    }

    if (rsp.nodeRequests != null) {
      // extended processing per node
      for (StramToNodeRequest req: rsp.nodeRequests) {
        ComponentContextPair<Node, NodeContext> pair = nodes.get(req.getNodeId());
        if (pair == null) {
          logger.warn("Received request with invalid node id {} ({})", req.getNodeId(), req);
        }
        else {
          logger.debug("Stram request: {}", req);
          processStramRequest(pair, req);
        }
      }
    }
  }

  /**
   * Process request from stram for further communication through the protocol. Extended reporting is on a per node basis (won't occur under regular operation)
   *
   * @param n
   * @param snr
   */
  private void processStramRequest(ComponentContextPair<Node, NodeContext> pair, StramToNodeRequest snr)
  {
    switch (snr.getRequestType()) {
      case REPORT_PARTION_STATS:
        logger.warn("Ignoring stram request {}", snr);
        break;

      case CHECKPOINT:
        pair.context.requestBackup(new HdfsBackupAgent(StramUtils.getNodeSerDe(null)));
        break;

      default:
        logger.error("Unknown request from stram {}", snr);
    }
  }

  private synchronized void deploy(List<NodeDeployInfo> nodeList)
  {
    /**
     * A little bit of up front sanity check would reduce the percentage of deploy failures later.
     */
    HashMap<String, ArrayList<String>> groupedInputStreams = new HashMap<String, ArrayList<String>>();
    for (NodeDeployInfo ndi: nodeList) {
      if (nodes.containsKey(ndi.id)) {
        throw new IllegalStateException("Node with id: " + ndi.id + " already present in the container");
      }
      groupInputStreams(groupedInputStreams, ndi);
    }

    deployNodes(nodeList);
    deployOutputStreams(nodeList, groupedInputStreams);
    deployInputStreams(nodeList);

    activate(nodeList);
  }

  private void deployNodes(List<NodeDeployInfo> nodeList) throws IllegalArgumentException
  {
    NodeSerDe nodeSerDe = StramUtils.getNodeSerDe(null);
    HdfsBackupAgent backupAgent = new HdfsBackupAgent(nodeSerDe);
    for (NodeDeployInfo ndi: nodeList) {
      NodeContext nc = new NodeContext(ndi.id);
      try {
        logger.debug("Restoring node {} to checkpoint {}", ndi.id, ndi.checkpointWindowId);
        final Object foreignObject;
        if (ndi.checkpointWindowId > 0) {
          foreignObject = backupAgent.restore(ndi.id, ndi.checkpointWindowId);
        }
        else {
          foreignObject = nodeSerDe.read(new ByteArrayInputStream(ndi.serializedNode));
        }
        Node node = (Node)foreignObject;
        node.setup(new NodeConfiguration(ndi.id, ndi.properties));
        nodes.put(ndi.id, new ComponentContextPair<Node, NodeContext>(node, nc));
      }
      catch (ClassCastException cce) {
        logger.error(cce.getLocalizedMessage());
        throw cce;
      }
      catch (IOException e) {
        throw new IllegalArgumentException("Failed to read object " + ndi, e);
      }
    }
  }

  private void deployOutputStreams(List<NodeDeployInfo> nodeList, HashMap<String, ArrayList<String>> groupedInputStreams)
  {
    /**
     * We proceed to deploy all the output streams.
     * At the end of this block, our streams collection will contain all the streams which originate at the
     * output port of the nodes. The streams are generally mapped against the "nodename.portname" string.
     * But the BufferOutputStreams which share the output port with other inline streams are mapped against
     * the Buffer Server port to avoid collision and at the same time keep track of these buffer streams.
     */
    for (NodeDeployInfo ndi: nodeList) {
      Node node = nodes.get(ndi.id).component;

      for (NodeDeployInfo.NodeOutputDeployInfo nodi: ndi.outputs) {
        String sourceIdentifier = ndi.id.concat(NODE_PORT_CONCAT_SEPARATOR).concat(nodi.portName);
        String sinkIdentifier;

        Stream stream;

        ArrayList<String> collection = groupedInputStreams.get(sourceIdentifier);
        if (collection == null) {
          /**
           * Let's create a stream to carry the data to the Buffer Server.
           * Nobody in this container is interested in the output placed on this stream, but
           * this stream exists. That means someone outside of this container must be interested.
           */
          assert (nodi.isInline() == false);

          StreamConfiguration config = new StreamConfiguration();
          config.setSocketAddr(StreamConfiguration.SERVER_ADDRESS, InetSocketAddress.createUnresolved(nodi.bufferServerHost, nodi.bufferServerPort));

          stream = new BufferServerOutputStream(StramUtils.getSerdeInstance(nodi.serDeClassName));
          stream.setup(config);
          logger.debug("deployed a buffer stream {}", stream);

          sinkIdentifier = "tcp://".concat(nodi.bufferServerHost).concat(":").concat(String.valueOf(nodi.bufferServerPort)).concat("/").concat(sourceIdentifier);
        }
        else if (collection.size() == 1) {
          if (nodi.isInline()) {
            /**
             * Let's create an inline stream to carry data from output port to input port of some other node.
             * There is only one node interested in output placed on this stream, and that node is in this container.
             */
            stream = new InlineStream();
            stream.setup(new StreamConfiguration());

            sinkIdentifier = null;
          }
          else {
            /**
             * Let's create 2 streams: 1 inline and 1 going to the Buffer Server.
             * Although there is a node in this container interested in output placed on this stream, there
             * seems to at least one more party interested but placed in a container other than this one.
             */
            stream = new MuxStream();
            stream.setup(new StreamConfiguration());

            StreamConfiguration config = new StreamConfiguration();
            config.setSocketAddr(StreamConfiguration.SERVER_ADDRESS, InetSocketAddress.createUnresolved(nodi.bufferServerHost, nodi.bufferServerPort));

            BufferServerOutputStream bsos = new BufferServerOutputStream(StramUtils.getSerdeInstance(nodi.serDeClassName));
            bsos.setup(config);
            logger.debug("deployed a buffer stream {}", bsos);

            // the following sinkIdentifier may not gel well with the rest of the logic
            sinkIdentifier = "tcp://".concat(nodi.bufferServerHost).concat(":").concat(String.valueOf(nodi.bufferServerPort)).concat("/").concat(sourceIdentifier);

            StreamContext bssc = new StreamContext(nodi.declaredStreamId);
            bssc.setSourceId(sourceIdentifier);
            bssc.setSinkId(sinkIdentifier);

            Sink s = bsos.connect(Component.INPUT, stream);
            stream.connect(sinkIdentifier, s);

            streams.put(sinkIdentifier, new ComponentContextPair<Stream, StreamContext>(bsos, bssc));
            logger.debug("stored stream {} against key {}", bsos, sinkIdentifier);
          }
        }
        else {
          /**
           * Since there are multiple parties interested in this node itself, we are going to come
           * to this block multiple times. The actions we take subsequent times are going to be different
           * than the first time. We create the MuxStream only the first time.
           */
          ComponentContextPair<Stream, StreamContext> pair = streams.get(sourceIdentifier);
          if (pair == null) {
            /**
             * Let's multiplex the output placed on this stream.
             * This container itself contains more than one parties interested.
             */
            stream = new MuxStream();
            stream.setup(new StreamConfiguration());
          }
          else {
            stream = pair.component;
          }

          if (nodi.isInline()) {
            sinkIdentifier = null;
          }
          else {
            StreamConfiguration config = new StreamConfiguration();
            config.setSocketAddr(StreamConfiguration.SERVER_ADDRESS, InetSocketAddress.createUnresolved(nodi.bufferServerHost, nodi.bufferServerPort));

            BufferServerOutputStream bsos = new BufferServerOutputStream(StramUtils.getSerdeInstance(nodi.serDeClassName));
            bsos.setup(config);
            logger.debug("deployed a buffer stream {}", bsos);

            sinkIdentifier = "tcp://".concat(nodi.bufferServerHost).concat(":").concat(String.valueOf(nodi.bufferServerPort)).concat("/").concat(sourceIdentifier);

            StreamContext bssc = new StreamContext(nodi.declaredStreamId);
            bssc.setSourceId(sourceIdentifier);
            bssc.setSinkId(sinkIdentifier);

            Sink s = bsos.connect(Component.INPUT, stream);
            stream.connect(sinkIdentifier, s);

            streams.put(sinkIdentifier, new ComponentContextPair<Stream, StreamContext>(bsos, bssc));
            logger.debug("stored stream {} against key {}", bsos, sinkIdentifier);
          }
        }

        if (!streams.containsKey(sourceIdentifier)) {
          Sink s = stream.connect(Component.INPUT, node);
          node.connect(nodi.portName, s);

          StreamContext context = new StreamContext(nodi.declaredStreamId);
          context.setSourceId(sourceIdentifier);
          context.setSinkId(sinkIdentifier);

          streams.put(sourceIdentifier, new ComponentContextPair<Stream, StreamContext>(stream, context));
          logger.debug("stored stream {} against key {}", stream, sourceIdentifier);
        }
      }
    }
  }

  private void deployInputStreams(List<NodeDeployInfo> nodeList)
  {
    // collect any input nodes along with their smallest window id,
    // those are subsequently used to setup the window generator
    ArrayList<NodeDeployInfo> inputNodes = new ArrayList<NodeDeployInfo>();
    long smallestWindowId = Long.MAX_VALUE;

    /**
     * Hook up all the downstream sinks.
     * There are 2 places where we deal with more than sinks. The first one follows immediately for WindowGenerator.
     * The second case is when source for the input of some node in this container is another container. So we need
     * to create the stream. We need to track this stream along with other streams, and many such streams may exist,
     * we hash them against buffer server info as we did for outputs but throw in the sinkid in the mix as well.
     */
    for (NodeDeployInfo ndi: nodeList) {
      if (ndi.inputs == null || ndi.inputs.isEmpty()) {
        /**
         * This has to be AbstractInputNode, so let's hook the WindowGenerator to it.
         * A node which does not take any input cannot exist in the DAG since it would be completely
         * unaware of the windows. So for that reason, AbstractInputNode allows Component.INPUT port.
         */
        inputNodes.add(ndi);
        /**
         * When we activate the window Generator, we plan to activate it only from required windowId.
         */
        if (ndi.checkpointWindowId < smallestWindowId) {
          smallestWindowId = ndi.checkpointWindowId;
        }
      }
      else {
        Node node = nodes.get(ndi.id).component;

        for (NodeDeployInfo.NodeInputDeployInfo nidi: ndi.inputs) {
          String sourceIdentifier = nidi.sourceNodeId.concat(NODE_PORT_CONCAT_SEPARATOR).concat(nidi.sourcePortName);
          String sinkIdentifier = ndi.id.concat(NODE_PORT_CONCAT_SEPARATOR).concat(nidi.portName);

          ComponentContextPair<Stream, StreamContext> pair = streams.get(sourceIdentifier);
          if (pair == null) {
            /**
             * We connect to the buffer server for the input on this port.
             * We have already placed all the output streams for all the nodes in this container yet, there is no stream
             * which can source this port so it has to come from the buffer server, so let's make a connection to it.
             */
            assert (nidi.isInline() == false);

            StreamConfiguration config = new StreamConfiguration();
            config.setSocketAddr(StreamConfiguration.SERVER_ADDRESS, InetSocketAddress.createUnresolved(nidi.bufferServerHost, nidi.bufferServerPort));

            Stream stream = new BufferServerInputStream(StramUtils.getSerdeInstance(nidi.serDeClassName));
            stream.setup(config);
            logger.debug("deployed buffer input stream {}", stream);

            StreamContext context = new StreamContext(nidi.declaredStreamId);
            context.setPartitions(nidi.partitionKeys);
            context.setSourceId(sourceIdentifier);
            context.setSinkId(sinkIdentifier);

            Sink s = node.connect(nidi.portName, stream);
            stream.connect(sinkIdentifier, ndi.checkpointWindowId > 0 ? new WindowIdActivatedSink(stream, sinkIdentifier, s, ndi.checkpointWindowId) : s);

            streams.put(sinkIdentifier,
                        new ComponentContextPair<Stream, StreamContext>(stream, context));
            logger.debug("put input stream {} against key {}", stream, sinkIdentifier);
          }
          else {
            String streamSinkId = pair.context.getSinkId();
            Sink s;

            if (streamSinkId == null) {
              s = node.connect(nidi.portName, pair.component);
              pair.context.setSinkId(sinkIdentifier);
            }
            else if (pair.component.isMultiSinkCapable()) {
              s = node.connect(nidi.portName, pair.component);
              pair.context.setSinkId(streamSinkId.concat(", ").concat(sinkIdentifier));
            }
            else {
              /**
               * we are trying to tap into existing InlineStream or BufferServerOutputStream.
               * Since none of those streams are MultiSinkCapable, we need to replace them with Mux.
               */
              StreamContext context = new StreamContext(nidi.declaredStreamId);
              context.setSourceId(sourceIdentifier);
              context.setSinkId(streamSinkId.concat(", ").concat(sinkIdentifier));

              Stream stream = new MuxStream();
              stream.setup(new StreamConfiguration());
              logger.debug("deployed input mux stream {}", stream);
              s = node.connect(nidi.portName, stream);
              streams.put(sourceIdentifier, new ComponentContextPair<Stream, StreamContext>(stream, context));
              logger.debug("stored input stream {} against key {}", stream, sourceIdentifier);

              /**
               * Lets wire the MuxStream to upstream node.
               */
              String[] nodeport = sourceIdentifier.split(NODE_PORT_SPLIT_SEPARATOR);
              Node upstreamNode = nodes.get(nodeport[0]).component;
              Sink muxSink = stream.connect(Component.INPUT, upstreamNode);
              upstreamNode.connect(nodeport[1], muxSink);

              Sink existingSink;
              if (pair.component instanceof InlineStream) {
                String[] np = streamSinkId.split(NODE_PORT_SPLIT_SEPARATOR);
                Node anotherNode = nodes.get(np[0]).component;
                existingSink = anotherNode.connect(np[1], stream);

                /*
                 * we do not need to do this but looks bad if leave it in limbo.
                 */
                pair.component.deactivate();
                pair.component.teardown();
              }
              else {
                existingSink = pair.component.connect(Component.INPUT, stream);

                /*
                 * we got this stream since it was mapped against sourceId, but since
                 * we took that place for MuxStream, we give this a new place of its own.
                 */
                streams.put(pair.context.getSinkId(), pair);
                logger.debug("relocated stream {} against key {}", pair.context.getSinkId());
              }

              stream.connect(streamSinkId, existingSink);
            }

            if (nidi.partitionKeys == null || nidi.partitionKeys.isEmpty()) {
              pair.component.connect(sinkIdentifier,
                                     ndi.checkpointWindowId > 0 ? new WindowIdActivatedSink(pair.component, sinkIdentifier, s, ndi.checkpointWindowId) : s);
            }
            else {
              /*
               * generally speaking we do not have partitions on the inline streams so the control should not
               * come here but if it comes, then we are ready to handle it using the partition aware streams.
               */
              PartitionAwareSink pas = new PartitionAwareSink(StramUtils.getSerdeInstance(nidi.serDeClassName), nidi.partitionKeys, s);
              pair.component.connect(sinkIdentifier,
                                     ndi.checkpointWindowId > 0 ? new WindowIdActivatedSink(pair.component, sinkIdentifier, pas, ndi.checkpointWindowId) : pas);
            }
          }
        }
      }
    }

    if (!inputNodes.isEmpty()) {
      WindowGenerator windowGenerator = setupWindowGenerator(smallestWindowId);
      for (NodeDeployInfo ndi: inputNodes) {
        generators.put(ndi.id, windowGenerator);

        Node node = nodes.get(ndi.id).component;
        Sink s = node.connect(Component.INPUT, windowGenerator);
        windowGenerator.connect(ndi.id.concat(NODE_PORT_CONCAT_SEPARATOR).concat(Component.INPUT),
                                ndi.checkpointWindowId > 0
                                ? new WindowIdActivatedSink(windowGenerator, ndi.id.concat(NODE_PORT_CONCAT_SEPARATOR).concat(Component.INPUT), s, ndi.checkpointWindowId)
                                : s);
      }
    }
  }

  /**
   * Create the window generator for the given start window id.
   * This is a hook for tests to control the window generation.
   *
   * @param smallestWindowId
   * @return
   */
  protected WindowGenerator setupWindowGenerator(long smallestWindowId)
  {
    WindowGenerator windowGenerator = new WindowGenerator(new ScheduledThreadPoolExecutor(1));
    /**
     * let's make sure that we send the same window Ids with the same reset windows.
     */
    // let's see if we want to send the exact same window id even the second time.
    NodeConfiguration config = new NodeConfiguration("doesn't matter", null);
    config.setLong(WindowGenerator.RESET_WINDOW_MILLIS, firstWindowMillis);
    if (smallestWindowId > firstWindowMillis) {
      config.setLong(WindowGenerator.FIRST_WINDOW_MILLIS, (smallestWindowId >> 32) * 1000 + windowWidthMillis * (smallestWindowId & WindowGenerator.MAX_WINDOW_ID));
    }
    else {
      config.setLong(WindowGenerator.FIRST_WINDOW_MILLIS, firstWindowMillis);
    }
    config.setInt(WindowGenerator.WINDOW_WIDTH_MILLIS, windowWidthMillis);
    windowGenerator.setup(config);
    return windowGenerator;
  }

  @SuppressWarnings({"SleepWhileInLoop", "SleepWhileHoldingLock"})
  public synchronized void activate(List<NodeDeployInfo> nodeList)
  {
    for (ComponentContextPair<Stream, StreamContext> pair: streams.values()) {
      if (!(pair.component instanceof SocketInputStream || activeStreams.containsKey(pair.component))) {
        activeStreams.put(pair.component, pair.context);
        pair.component.activate(pair.context);
      }
    }

    final AtomicInteger activatedNodeCount = new AtomicInteger(activeNodes.size());
    for (final NodeDeployInfo ndi: nodeList) {
      final ComponentContextPair<Node, NodeContext> pair = nodes.get(ndi.id);
      assert (!activeNodes.containsKey(pair.component));
      logger.debug("activating node {} in container {}", pair.component.toString(), containerId);
      new Thread(pair.component.toString())
      {
        @Override
        public void run()
        {
          activatedNodeCount.incrementAndGet();
          activeNodes.put(pair.component, pair.context);
          pair.component.activate(pair.context);
          activeNodes.remove(pair.component);
          disconnectNode(ndi.id);
        }
      }.start();
    }

    /**
     * we need to make sure that before any of the nodes gets the first message, it's activated.
     */
    try {
      do {
        Thread.sleep(20);
      }
      while (activatedNodeCount.get() < nodes.size());
    }
    catch (InterruptedException ex) {
      logger.debug(ex.getLocalizedMessage());
    }

    for (ComponentContextPair<Stream, StreamContext> pair: streams.values()) {
      if (pair.component instanceof SocketInputStream && !activeStreams.containsKey(pair.component)) {
        activeStreams.put(pair.component, pair.context);
        pair.component.activate(pair.context);
      }
    }

    for (WindowGenerator wg: generators.values()) {
      if (!activeGenerators.containsKey(wg)) {
        activeGenerators.put(wg, generators);
        wg.activate(null);
      }
    }
  }

  private void groupInputStreams(HashMap<String, ArrayList<String>> groupedInputStreams, NodeDeployInfo ndi)
  {
    for (NodeDeployInfo.NodeInputDeployInfo nidi: ndi.inputs) {
      String source = nidi.sourceNodeId.concat(NODE_PORT_CONCAT_SEPARATOR).concat(nidi.sourcePortName);

      /**
       * if we do not want to combine multiple streams with different partitions from the
       * same upstream node, we could also use the partition to group the streams together.
       * This logic comes with the danger that the performance of the group which shares the same
       * stream is bounded on the higher side by the performance of the lowest performer. May be
       * combining the streams is not such a good thing but let's see if we allow this as an option
       * to the user, what they end up choosing the most.
       */
      ArrayList<String> collection = groupedInputStreams.get(source);
      if (collection == null) {
        collection = new ArrayList<String>();
        groupedInputStreams.put(source, collection);
      }
      collection.add(ndi.id.concat(NODE_PORT_CONCAT_SEPARATOR).concat(nidi.portName));
    }
  }

  private class HdfsBackupAgent implements BackupAgent
  {
    private final NodeSerDe serDe;

    private HdfsBackupAgent(NodeSerDe serde)
    {
      serDe = serde;
    }

    @Override
    public void backup(String nodeId, long windowId, Object o) throws IOException
    {
      FileSystem fs = FileSystem.get(conf);
      Path path = new Path(StramChild.this.checkpointDfsPath + "/" + nodeId + "/" + windowId);
      logger.debug("Backup path: {}", path);
      FSDataOutputStream output = fs.create(path);
      try {
        serDe.write(o, output);
        // record last backup window id for heartbeat
        StramChild.this.backupInfo.put(nodeId, windowId);
      }
      finally {
        output.close();
      }

    }

    @Override
    public Object restore(String id, long windowId) throws IOException
    {
      FileSystem fs = FileSystem.get(conf);
      FSDataInputStream input = fs.open(new Path(StramChild.this.checkpointDfsPath + "/" + id + "/" + windowId));
      try {
        return serDe.read(input);
      }
      finally {
        input.close();
      }
    }
  }
}
