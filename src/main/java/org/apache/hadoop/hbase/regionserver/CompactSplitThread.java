/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.regionserver;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.util.StringUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Compact region on request and then run split if appropriate
 */
class CompactSplitThread extends Thread {
  static final Log LOG = LogFactory.getLog(CompactSplitThread.class);

  private HTable root = null;
  private HTable meta = null;
  private final long frequency;
  private final ReentrantLock lock = new ReentrantLock();

  private final HRegionServer server;
  private final Configuration conf;

  private final PriorityCompactionQueue compactionQueue =
    new PriorityCompactionQueue();

  /** The priorities for a compaction request. */
  public enum Priority implements Comparable<Priority> {
    //NOTE: All priorities should be numbered consecutively starting with 1.
    //The highest priority should be 1 followed by all lower priorities.
    //Priorities can be changed at anytime without requiring any changes to the
    //queue.

    /** HIGH_BLOCKING should only be used when an operation is blocked until a
     * compact / split is done (e.g. a MemStore can't flush because it has
     * "too many store files" and is blocking until a compact / split is done)
     */
    HIGH_BLOCKING(1),
    /** A normal compaction / split request */
    NORMAL(2),
    /** A low compaction / split request -- not currently used */
    LOW(3);

    int value;

    Priority(int value) {
      this.value = value;
    }

    int getInt() {
      return value;
    }
  }

  /** @param server */
  public CompactSplitThread(HRegionServer server) {
    super();
    this.server = server;
    this.conf = server.conf;
    this.frequency =
      conf.getLong("hbase.regionserver.thread.splitcompactcheckfrequency",
      20 * 1000);
  }

  @Override
  public void run() {
    while (!this.server.isStopRequested()) {
      HRegion r = null;
      try {
        r = compactionQueue.poll(this.frequency, TimeUnit.MILLISECONDS);
        if (r != null) {
          lock.lock();
          try {
            if(!this.server.isStopRequested()) {
              // Don't interrupt us while we are working
              byte [] midKey = r.compactStores();
              LOG.debug("Just finished a compaction. " +
                        " Current Compaction Queue Size: " +
                        getCompactionQueueSize());
              if (midKey != null && !this.server.isStopRequested()) {
                split(r, midKey);
              }
            }
          } finally {
            lock.unlock();
          }
        }
      } catch (InterruptedException ex) {
        continue;
      } catch (IOException ex) {
        LOG.error("Compaction/Split failed for region " +
            r.getRegionNameAsString(),
          RemoteExceptionHandler.checkIOException(ex));
        if (!server.checkFileSystem()) {
          break;
        }
      } catch (Exception ex) {
        LOG.error("Compaction failed" +
            (r != null ? (" for region " + r.getRegionNameAsString()) : ""),
            ex);
        if (!server.checkFileSystem()) {
          break;
        }
      }
    }
    compactionQueue.clear();
    LOG.info(getName() + " exiting");
  }

  /**
   * @param r HRegion store belongs to
   * @param why Why compaction requested -- used in debug messages
   */
  public synchronized void requestCompaction(final HRegion r,
      final String why) {
    requestCompaction(r, false, why, Priority.NORMAL);
  }

  public synchronized void requestCompaction(final HRegion r,
      final String why, Priority p) {
    requestCompaction(r, false, why, p);
  }

  public synchronized void requestCompaction(final HRegion r,
      final boolean force, final String why) {
    requestCompaction(r, force, why, Priority.NORMAL);
  }


  /**
   * @param r HRegion store belongs to
   * @param force Whether next compaction should be major
   * @param why Why compaction requested -- used in debug messages
   */
  public synchronized void requestCompaction(final HRegion r,
      final boolean force, final String why, Priority priority) {

    boolean addedToQueue = false;

    if (this.server.stopRequested.get()) {
      return;
    }

    r.setForceMajorCompaction(force);

    addedToQueue = compactionQueue.add(r, priority);

    // only log if actually added to compaction queue...
    if (addedToQueue && LOG.isDebugEnabled()) {
      LOG.debug("Compaction " + (force? "(major) ": "") +
        "requested for region " + r.getRegionNameAsString() +
        "/" + r.getRegionInfo().getEncodedName() +
        (why != null && !why.isEmpty()? " because: " + why: "") +
        "; Priority: " + priority + "; Compaction queue size: " + compactionQueue.size());
    }
  }

  private void split(final HRegion region, final byte [] midKey)
  throws IOException {
    final HRegionInfo oldRegionInfo = region.getRegionInfo();
    final long startTime = System.currentTimeMillis();
    final HRegion[] newRegions = region.splitRegion(midKey);
    if (newRegions == null) {
      // Didn't need to be split
      return;
    }

    // When a region is split, the META table needs to updated if we're
    // splitting a 'normal' region, and the ROOT table needs to be
    // updated if we are splitting a META region.
    HTable t = null;
    if (region.getRegionInfo().isMetaTable()) {
      // We need to update the root region
      if (this.root == null) {
        this.root = new HTable(conf, HConstants.ROOT_TABLE_NAME);
      }
      t = root;
    } else {
      // For normal regions we need to update the meta region
      if (meta == null) {
        meta = new HTable(conf, HConstants.META_TABLE_NAME);
      }
      t = meta;
    }

    // Mark old region as offline and split in META.
    // NOTE: there is no need for retry logic here. HTable does it for us.
    oldRegionInfo.setOffline(true);
    oldRegionInfo.setSplit(true);
    // Inform the HRegionServer that the parent HRegion is no-longer online.
    this.server.removeFromOnlineRegions(oldRegionInfo);

    Put put = new Put(oldRegionInfo.getRegionName());
    put.add(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER,
      Writables.getBytes(oldRegionInfo));
    put.add(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER,
        HConstants.EMPTY_BYTE_ARRAY);
    put.add(HConstants.CATALOG_FAMILY, HConstants.STARTCODE_QUALIFIER,
        HConstants.EMPTY_BYTE_ARRAY);
    put.add(HConstants.CATALOG_FAMILY, HConstants.SPLITA_QUALIFIER,
      Writables.getBytes(newRegions[0].getRegionInfo()));
    put.add(HConstants.CATALOG_FAMILY, HConstants.SPLITB_QUALIFIER,
      Writables.getBytes(newRegions[1].getRegionInfo()));
    t.put(put);

    // If we crash here, then the daughters will not be added and we'll have
    // and offlined parent but no daughters to take up the slack.  hbase-2244
    // adds fixup to the metascanners.

    // Add new regions to META
    for (int i = 0; i < newRegions.length; i++) {
      put = new Put(newRegions[i].getRegionName());
      put.add(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER,
          Writables.getBytes(newRegions[i].getRegionInfo()));
      t.put(put);
    }

    // If we crash here, the master will not know of the new daughters and they
    // will not be assigned.  The metascanner when it runs will notice and take
    // care of assigning the new daughters.

    // Now tell the master about the new regions
    server.reportSplit(oldRegionInfo, newRegions[0].getRegionInfo(),
      newRegions[1].getRegionInfo());

    LOG.info("region split, META updated, and report to master all" +
      " successful. Old region=" + oldRegionInfo.toString() +
      ", new regions: " + newRegions[0].toString() + ", " +
      newRegions[1].toString() + ". Split took " +
      StringUtils.formatTimeDiff(System.currentTimeMillis(), startTime));
  }

  /**
   * Only interrupt once it's done with a run through the work loop.
   */
  void interruptIfNecessary() {
    if (lock.tryLock()) {
      try {
        this.interrupt();
      } finally {
        lock.unlock();
      }
    }
  }

  /**
   * Returns the current size of the queue containing regions that are
   * processed.
   *
   * @return The current size of the regions queue.
   */
  public int getCompactionQueueSize() {
    return compactionQueue.size();
  }
}
