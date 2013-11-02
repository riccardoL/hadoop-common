/**
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
package org.apache.hadoop.hdfs.server.blockmanagement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.hdfs.server.protocol.StorageReport;
import org.apache.hadoop.hdfs.server.namenode.CachedBlock;
import org.apache.hadoop.hdfs.util.LightWeightHashSet;
import org.apache.hadoop.util.IntrusiveCollection;
import org.apache.hadoop.util.Time;

/**
 * This class extends the DatanodeInfo class with ephemeral information (eg
 * health, capacity, what blocks are associated with the Datanode) that is
 * private to the Namenode, ie this class is not exposed to clients.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class DatanodeDescriptor extends DatanodeInfo {
  public static final Log LOG = LogFactory.getLog(DatanodeDescriptor.class);
  public static final DatanodeDescriptor[] EMPTY_ARRAY = {};

  // Stores status of decommissioning.
  // If node is not decommissioning, do not use this object for anything.
  public DecommissioningStatus decommissioningStatus = new DecommissioningStatus();
  
  /** Block and targets pair */
  @InterfaceAudience.Private
  @InterfaceStability.Evolving
  public static class BlockTargetPair {
    public final Block block;
    public final DatanodeStorageInfo[] targets;    

    BlockTargetPair(Block block, DatanodeStorageInfo[] targets) {
      this.block = block;
      this.targets = targets;
    }
  }

  /** A BlockTargetPair queue. */
  private static class BlockQueue<E> {
    private final Queue<E> blockq = new LinkedList<E>();

    /** Size of the queue */
    synchronized int size() {return blockq.size();}

    /** Enqueue */
    synchronized boolean offer(E e) { 
      return blockq.offer(e);
    }

    /** Dequeue */
    synchronized List<E> poll(int numBlocks) {
      if (numBlocks <= 0 || blockq.isEmpty()) {
        return null;
      }

      List<E> results = new ArrayList<E>();
      for(; !blockq.isEmpty() && numBlocks > 0; numBlocks--) {
        results.add(blockq.poll());
      }
      return results;
    }

    /**
     * Returns <tt>true</tt> if the queue contains the specified element.
     */
    boolean contains(E e) {
      return blockq.contains(e);
    }

    synchronized void clear() {
      blockq.clear();
    }
  }

  private final Map<String, DatanodeStorageInfo> storageMap = 
      new HashMap<String, DatanodeStorageInfo>();

  /**
   * A list of CachedBlock objects on this datanode.
   */
  public static class CachedBlocksList extends IntrusiveCollection<CachedBlock> {
    public enum Type {
      PENDING_CACHED,
      CACHED,
      PENDING_UNCACHED
    }

    private final DatanodeDescriptor datanode;

    private final Type type;

    CachedBlocksList(DatanodeDescriptor datanode, Type type) {
      this.datanode = datanode;
      this.type = type;
    }

    public DatanodeDescriptor getDatanode() {
      return datanode;
    }

    public Type getType() {
      return type;
    }
  }

  /**
   * The blocks which we want to cache on this DataNode.
   */
  private final CachedBlocksList pendingCached = 
      new CachedBlocksList(this, CachedBlocksList.Type.PENDING_CACHED);

  /**
   * The blocks which we know are cached on this datanode.
   * This list is updated by periodic cache reports.
   */
  private final CachedBlocksList cached = 
      new CachedBlocksList(this, CachedBlocksList.Type.CACHED);

  /**
   * The blocks which we want to uncache on this DataNode.
   */
  private final CachedBlocksList pendingUncached = 
      new CachedBlocksList(this, CachedBlocksList.Type.PENDING_UNCACHED);

  public CachedBlocksList getPendingCached() {
    return pendingCached;
  }

  public CachedBlocksList getCached() {
    return cached;
  }

  public CachedBlocksList getPendingUncached() {
    return pendingUncached;
  }

  // isAlive == heartbeats.contains(this)
  // This is an optimization, because contains takes O(n) time on Arraylist
  public boolean isAlive = false;
  public boolean needKeyUpdate = false;

  
  // A system administrator can tune the balancer bandwidth parameter
  // (dfs.balance.bandwidthPerSec) dynamically by calling
  // "dfsadmin -setBalanacerBandwidth <newbandwidth>", at which point the
  // following 'bandwidth' variable gets updated with the new value for each
  // node. Once the heartbeat command is issued to update the value on the
  // specified datanode, this value will be set back to 0.
  private long bandwidth;

  /** A queue of blocks to be replicated by this datanode */
  private BlockQueue<BlockTargetPair> replicateBlocks = new BlockQueue<BlockTargetPair>();
  /** A queue of blocks to be recovered by this datanode */
  private BlockQueue<BlockInfoUnderConstruction> recoverBlocks =
                                new BlockQueue<BlockInfoUnderConstruction>();
  /** A set of blocks to be invalidated by this datanode */
  private LightWeightHashSet<Block> invalidateBlocks = new LightWeightHashSet<Block>();

  private int volumeFailures = 0;
  
  /** 
   * When set to true, the node is not in include list and is not allowed
   * to communicate with the namenode
   */
  private boolean disallowed = false;

  /**
   * DatanodeDescriptor constructor
   * @param nodeID id of the data node
   */
  public DatanodeDescriptor(DatanodeID nodeID) {
    super(nodeID);
  }

  /**
   * DatanodeDescriptor constructor
   * @param nodeID id of the data node
   * @param networkLocation location of the data node in network
   */
  public DatanodeDescriptor(DatanodeID nodeID, 
                            String networkLocation) {
    super(nodeID, networkLocation);
  }

  /**
   * Add data-node to the block. Add block to the head of the list of blocks
   * belonging to the data-node.
   */
  public boolean addBlock(String storageID, BlockInfo b) {
    DatanodeStorageInfo s = getStorageInfo(storageID);
    if (s != null) {
      return s.addBlock(b);
    }
    return false;
  }

  DatanodeStorageInfo getStorageInfo(String storageID) {
    synchronized (storageMap) {
      return storageMap.get(storageID);
    }
  }
  DatanodeStorageInfo[] getStorageInfos() {
    synchronized (storageMap) {
      final Collection<DatanodeStorageInfo> storages = storageMap.values();
      return storages.toArray(new DatanodeStorageInfo[storages.size()]);
    }
  }

  /**
   * Remove block from the list of blocks belonging to the data-node. Remove
   * data-node from the block.
   */
  boolean removeBlock(BlockInfo b) {
    int index = b.findStorageInfo(this);
    // if block exists on this datanode
    if (index >= 0) {
      DatanodeStorageInfo s = b.getStorageInfo(index);
      if (s != null) {
        return s.removeBlock(b);
      }
    }
    return false;
  }
  
  /**
   * Remove block from the list of blocks belonging to the data-node. Remove
   * data-node from the block.
   */
  boolean removeBlock(String storageID, BlockInfo b) {
    DatanodeStorageInfo s = getStorageInfo(storageID);
    if (s != null) {
      return s.removeBlock(b);
    }
    return false;
  }

  /**
   * Replace specified old block with a new one in the DataNodeDescriptor.
   *
   * @param oldBlock - block to be replaced
   * @param newBlock - a replacement block
   * @return the new block
   */
  public BlockInfo replaceBlock(BlockInfo oldBlock, BlockInfo newBlock) {
    int index = oldBlock.findStorageInfo(this);
    DatanodeStorageInfo s = oldBlock.getStorageInfo(index);
    boolean done = s.removeBlock(oldBlock);
    assert done : "Old block should belong to the data-node when replacing";

    done = s.addBlock(newBlock);
    assert done : "New block should not belong to the data-node when replacing";
    return newBlock;
  }

  public void resetBlocks() {
    setCapacity(0);
    setRemaining(0);
    setBlockPoolUsed(0);
    setDfsUsed(0);
    setXceiverCount(0);
    this.invalidateBlocks.clear();
    this.volumeFailures = 0;
    // pendingCached, cached, and pendingUncached are protected by the
    // FSN lock.
    this.pendingCached.clear();
    this.cached.clear();
    this.pendingUncached.clear();
  }
  
  public void clearBlockQueues() {
    synchronized (invalidateBlocks) {
      this.invalidateBlocks.clear();
      this.recoverBlocks.clear();
      this.replicateBlocks.clear();
    }
    // pendingCached, cached, and pendingUncached are protected by the
    // FSN lock.
    this.pendingCached.clear();
    this.cached.clear();
    this.pendingUncached.clear();
  }

  public int numBlocks() {
    int blocks = 0;
    for (DatanodeStorageInfo entry : getStorageInfos()) {
      blocks += entry.numBlocks();
    }
    return blocks;
  }

  /**
   * Updates stats from datanode heartbeat.
   */
  public void updateHeartbeat(StorageReport[] reports, long cacheCapacity,
      long cacheUsed, int xceiverCount, int volFailures) {
    long totalCapacity = 0;
    long totalRemaining = 0;
    long totalBlockPoolUsed = 0;
    long totalDfsUsed = 0;

    setCacheCapacity(cacheCapacity);
    setCacheUsed(cacheUsed);
    setXceiverCount(xceiverCount);
    setLastUpdate(Time.now());    
    this.volumeFailures = volFailures;
    for (StorageReport report : reports) {
      DatanodeStorageInfo storage = storageMap.get(report.getStorageID());
      if (storage != null) {
        storage.receivedHeartbeat(report, getLastUpdate());
        totalCapacity += report.getCapacity();
        totalRemaining += report.getRemaining();
        totalBlockPoolUsed += report.getBlockPoolUsed();
        totalDfsUsed += report.getDfsUsed();
      } else {
        // This warning is generally benign during cluster initialization
        // when the heartbeat is received before the initial block reports
        // from each storage.
        LOG.warn("Unrecognized storage ID " + report.getStorageID());
      }
    }

    // Update total metrics for the node.
    setCapacity(totalCapacity);
    setRemaining(totalRemaining);
    setBlockPoolUsed(totalBlockPoolUsed);
    setDfsUsed(totalDfsUsed);
  }

  private static class BlockIterator implements Iterator<BlockInfo> {
    private int index = 0;
    private final List<Iterator<BlockInfo>> iterators;
    
    private BlockIterator(final DatanodeStorageInfo... storages) {
      List<Iterator<BlockInfo>> iterators = new ArrayList<Iterator<BlockInfo>>();
      for (DatanodeStorageInfo e : storages) {
        iterators.add(e.getBlockIterator());
      }
      this.iterators = Collections.unmodifiableList(iterators);
    }

    @Override
    public boolean hasNext() {
      update();
      return iterators.get(index).hasNext();
    }

    @Override
    public BlockInfo next() {
      update();
      return iterators.get(index).next();
    }
    
    @Override
    public void remove() {
      throw new UnsupportedOperationException("Remove unsupported.");
    }
    
    private void update() {
      while(index < iterators.size() - 1 && !iterators.get(index).hasNext()) {
        index++;
      }
    }
  }

  Iterator<BlockInfo> getBlockIterator() {
    return new BlockIterator(getStorageInfos());
  }
  Iterator<BlockInfo> getBlockIterator(final String storageID) {
    return new BlockIterator(getStorageInfo(storageID));
  }

  /**
   * Store block replication work.
   */
  void addBlockToBeReplicated(Block block, DatanodeStorageInfo[] targets) {
    assert(block != null && targets != null && targets.length > 0);
    replicateBlocks.offer(new BlockTargetPair(block, targets));
  }

  /**
   * Store block recovery work.
   */
  void addBlockToBeRecovered(BlockInfoUnderConstruction block) {
    if(recoverBlocks.contains(block)) {
      // this prevents adding the same block twice to the recovery queue
      BlockManager.LOG.info(block + " is already in the recovery queue");
      return;
    }
    recoverBlocks.offer(block);
  }

  /**
   * Store block invalidation work.
   */
  void addBlocksToBeInvalidated(List<Block> blocklist) {
    assert(blocklist != null && blocklist.size() > 0);
    synchronized (invalidateBlocks) {
      for(Block blk : blocklist) {
        invalidateBlocks.add(blk);
      }
    }
  }
  
  /**
   * The number of work items that are pending to be replicated
   */
  int getNumberOfBlocksToBeReplicated() {
    return replicateBlocks.size();
  }

  /**
   * The number of block invalidation items that are pending to 
   * be sent to the datanode
   */
  int getNumberOfBlocksToBeInvalidated() {
    synchronized (invalidateBlocks) {
      return invalidateBlocks.size();
    }
  }

  public List<BlockTargetPair> getReplicationCommand(int maxTransfers) {
    return replicateBlocks.poll(maxTransfers);
  }

  public BlockInfoUnderConstruction[] getLeaseRecoveryCommand(int maxTransfers) {
    List<BlockInfoUnderConstruction> blocks = recoverBlocks.poll(maxTransfers);
    if(blocks == null)
      return null;
    return blocks.toArray(new BlockInfoUnderConstruction[blocks.size()]);
  }

  /**
   * Remove the specified number of blocks to be invalidated
   */
  public Block[] getInvalidateBlocks(int maxblocks) {
    synchronized (invalidateBlocks) {
      Block[] deleteList = invalidateBlocks.pollToArray(new Block[Math.min(
          invalidateBlocks.size(), maxblocks)]);
      return deleteList.length == 0 ? null : deleteList;
    }
  }

  /**
   * @return Approximate number of blocks currently scheduled to be written 
   * to this datanode.
   */
  public int getBlocksScheduled() {
    int n = 0;
    for(DatanodeStorageInfo storage : getStorageInfos()) {
      n += storage.getBlocksScheduled();
    }
    return n;
  }
  
  @Override
  public int hashCode() {
    // Super implementation is sufficient
    return super.hashCode();
  }
  
  @Override
  public boolean equals(Object obj) {
    // Sufficient to use super equality as datanodes are uniquely identified
    // by DatanodeID
    return (this == obj) || super.equals(obj);
  }

  /** Decommissioning status */
  public class DecommissioningStatus {
    private int underReplicatedBlocks;
    private int decommissionOnlyReplicas;
    private int underReplicatedInOpenFiles;
    private long startTime;
    
    synchronized void set(int underRep,
        int onlyRep, int underConstruction) {
      if (isDecommissionInProgress() == false) {
        return;
      }
      underReplicatedBlocks = underRep;
      decommissionOnlyReplicas = onlyRep;
      underReplicatedInOpenFiles = underConstruction;
    }

    /** @return the number of under-replicated blocks */
    public synchronized int getUnderReplicatedBlocks() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return underReplicatedBlocks;
    }
    /** @return the number of decommission-only replicas */
    public synchronized int getDecommissionOnlyReplicas() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return decommissionOnlyReplicas;
    }
    /** @return the number of under-replicated blocks in open files */
    public synchronized int getUnderReplicatedInOpenFiles() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return underReplicatedInOpenFiles;
    }
    /** Set start time */
    public synchronized void setStartTime(long time) {
      startTime = time;
    }
    /** @return start time */
    public synchronized long getStartTime() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return startTime;
    }
  }  // End of class DecommissioningStatus

  /**
   * Set the flag to indicate if this datanode is disallowed from communicating
   * with the namenode.
   */
  public void setDisallowed(boolean flag) {
    disallowed = flag;
  }
  /** Is the datanode disallowed from communicating with the namenode? */
  public boolean isDisallowed() {
    return disallowed;
  }

  /**
   * @return number of failed volumes in the datanode.
   */
  public int getVolumeFailures() {
    return volumeFailures;
  }

  /**
   * @param nodeReg DatanodeID to update registration for.
   */
  @Override
  public void updateRegInfo(DatanodeID nodeReg) {
    super.updateRegInfo(nodeReg);
    
    // must re-process IBR after re-registration
    for(DatanodeStorageInfo storage : getStorageInfos()) {
      storage.setBlockReportCount(0);
    }
  }

  /**
   * @return balancer bandwidth in bytes per second for this datanode
   */
  public long getBalancerBandwidth() {
    return this.bandwidth;
  }

  /**
   * @param bandwidth balancer bandwidth in bytes per second for this datanode
   */
  public void setBalancerBandwidth(long bandwidth) {
    this.bandwidth = bandwidth;
  }

  @Override
  public String dumpDatanode() {
    StringBuilder sb = new StringBuilder(super.dumpDatanode());
    int repl = replicateBlocks.size();
    if (repl > 0) {
      sb.append(" ").append(repl).append(" blocks to be replicated;");
    }
    int inval = invalidateBlocks.size();
    if (inval > 0) {
      sb.append(" ").append(inval).append(" blocks to be invalidated;");      
    }
    int recover = recoverBlocks.size();
    if (recover > 0) {
      sb.append(" ").append(recover).append(" blocks to be recovered;");
    }
    return sb.toString();
  }

  @VisibleForTesting
  public DatanodeStorageInfo updateStorage(DatanodeStorage s) {
    synchronized (storageMap) {
      DatanodeStorageInfo storage = storageMap.get(s.getStorageID());
      if (storage == null) {
        storage = new DatanodeStorageInfo(this, s);
        storageMap.put(s.getStorageID(), storage);
      } else {
        storage.setState(s.getState());
      }
      return storage;
    }
  }
}

