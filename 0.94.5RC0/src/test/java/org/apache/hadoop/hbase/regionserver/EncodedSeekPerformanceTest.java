/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoderImpl;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoder;
import org.apache.hadoop.hbase.io.hfile.LruBlockCache;
import org.apache.hadoop.hbase.io.hfile.NoOpDataBlockEncoder;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;

/**
 * Test seek performance for encoded data blocks. Read an HFile and do several
 * random seeks.
 */
public class EncodedSeekPerformanceTest {
  private static final double NANOSEC_IN_SEC = 1000.0 * 1000.0 * 1000.0;
  private static final double BYTES_IN_MEGABYTES = 1024.0 * 1024.0;
  /** Default number of seeks which will be used in benchmark. */
  public static int DEFAULT_NUMBER_OF_SEEKS = 10000;

  private final HBaseTestingUtility testingUtility = new HBaseTestingUtility();
  private Configuration configuration = testingUtility.getConfiguration();
  private CacheConfig cacheConf = new CacheConfig(configuration);
  private Random randomizer;
  private int numberOfSeeks;

  /** Use this benchmark with default options */
  public EncodedSeekPerformanceTest() {
    configuration.setFloat(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY, 0.5f);
    randomizer = new Random(42l);
    numberOfSeeks = DEFAULT_NUMBER_OF_SEEKS;
  }

  private List<KeyValue> prepareListOfTestSeeks(Path path) throws IOException {
    List<KeyValue> allKeyValues = new ArrayList<KeyValue>();

    // read all of the key values
    StoreFile storeFile = new StoreFile(testingUtility.getTestFileSystem(),
        path, configuration, cacheConf, BloomType.NONE,
        NoOpDataBlockEncoder.INSTANCE);

    StoreFile.Reader reader = storeFile.createReader();
    StoreFileScanner scanner = reader.getStoreFileScanner(true, false);
    KeyValue current;

    scanner.seek(KeyValue.LOWESTKEY);
    while (null != (current = scanner.next())) {
      allKeyValues.add(current);
    }

    storeFile.closeReader(cacheConf.shouldEvictOnClose());

    // pick seeks by random
    List<KeyValue> seeks = new ArrayList<KeyValue>();
    for (int i = 0; i < numberOfSeeks; ++i) {
      KeyValue keyValue = allKeyValues.get(
          randomizer.nextInt(allKeyValues.size()));
      seeks.add(keyValue);
    }

    clearBlockCache();

    return seeks;
  }

  private void runTest(Path path, HFileDataBlockEncoder blockEncoder,
      List<KeyValue> seeks) throws IOException {
    // read all of the key values
    StoreFile storeFile = new StoreFile(testingUtility.getTestFileSystem(),
        path, configuration, cacheConf, BloomType.NONE, blockEncoder);

    long totalSize = 0;

    StoreFile.Reader reader = storeFile.createReader();
    StoreFileScanner scanner = reader.getStoreFileScanner(true, false);

    long startReadingTime = System.nanoTime();
    KeyValue current;
    scanner.seek(KeyValue.LOWESTKEY);
    while (null != (current = scanner.next())) { // just iterate it!
      if (current.getLength() < 0) {
        throw new IOException("Negative KV size: " + current);
      }
      totalSize += current.getLength();
    }
    long finishReadingTime = System.nanoTime();

    // do seeks
    long startSeeksTime = System.nanoTime();
    for (KeyValue keyValue : seeks) {
      scanner.seek(keyValue);
      KeyValue toVerify = scanner.next();
      if (!keyValue.equals(toVerify)) {
        System.out.println(String.format("KeyValue doesn't match:\n" +
            "Orig key: %s\n" +
            "Ret key:  %s", keyValue.getKeyString(), toVerify.getKeyString()));
        break;
      }
    }
    long finishSeeksTime = System.nanoTime();
    if (finishSeeksTime < startSeeksTime) {
      throw new AssertionError("Finish time " + finishSeeksTime +
          " is earlier than start time " + startSeeksTime);
    }

    // write some stats
    double readInMbPerSec = (totalSize * NANOSEC_IN_SEC) /
        (BYTES_IN_MEGABYTES * (finishReadingTime - startReadingTime));
    double seeksPerSec = (seeks.size() * NANOSEC_IN_SEC) /
        (finishSeeksTime - startSeeksTime);

    storeFile.closeReader(cacheConf.shouldEvictOnClose());
    clearBlockCache();

    System.out.println(blockEncoder);
    System.out.printf("  Read speed:       %8.2f (MB/s)\n", readInMbPerSec);
    System.out.printf("  Seeks per second: %8.2f (#/s)\n", seeksPerSec);
    System.out.printf("  Total KV size:    %d\n", totalSize);
  }

  /**
   * @param path Path to the HFile which will be used.
   * @param encoders List of encoders which will be used for tests.
   * @throws IOException if there is a bug while reading from disk
   */
  public void runTests(Path path, List<HFileDataBlockEncoder> encoders)
      throws IOException {
    List<KeyValue> seeks = prepareListOfTestSeeks(path);

    for (HFileDataBlockEncoder blockEncoder : encoders) {
      runTest(path, blockEncoder, seeks);
    }
  }

  /**
   * Command line interface:
   * @param args Takes one argument - file size.
   * @throws IOException if there is a bug while reading from disk
   */
  public static void main(final String[] args) throws IOException {
    if (args.length < 1) {
      printUsage();
      System.exit(-1);
    }

    Path path = new Path(args[0]);
    List<HFileDataBlockEncoder> encoders =
        new ArrayList<HFileDataBlockEncoder>();

    encoders.add(new HFileDataBlockEncoderImpl(DataBlockEncoding.NONE));
    for (DataBlockEncoding encodingAlgo : DataBlockEncoding.values()) {
      encoders.add(new HFileDataBlockEncoderImpl(DataBlockEncoding.NONE,
          encodingAlgo));
    }

    EncodedSeekPerformanceTest utility = new EncodedSeekPerformanceTest();
    utility.runTests(path, encoders);

    System.exit(0);
  }

  private static void printUsage() {
    System.out.println("Usage: one argument, name of the HFile");
  }

  private void clearBlockCache() {
    ((LruBlockCache) cacheConf.getBlockCache()).clearCache();
  }
}
