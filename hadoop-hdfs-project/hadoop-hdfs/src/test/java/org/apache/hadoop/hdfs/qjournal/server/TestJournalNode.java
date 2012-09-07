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
package org.apache.hadoop.hdfs.qjournal.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.qjournal.QJMTestUtil;
import org.apache.hadoop.hdfs.qjournal.client.IPCLoggerChannel;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.NewEpochResponseProto;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.PrepareRecoveryResponseProto;
import org.apache.hadoop.hdfs.qjournal.server.Journal;
import org.apache.hadoop.hdfs.qjournal.server.JournalNode;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.MetricsAsserts;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;


public class TestJournalNode {
  private static final NamespaceInfo FAKE_NSINFO = new NamespaceInfo(
      12345, "mycluster", "my-bp", 0L);
  private static final String JID = "test-journalid";

  private JournalNode jn;
  private Journal journal; 
  private Configuration conf = new Configuration();
  private IPCLoggerChannel ch;

  static {
    // Avoid an error when we double-initialize JvmMetrics
    DefaultMetricsSystem.setMiniClusterMode(true);
  }
  
  @Before
  public void setup() throws Exception {
    conf.set(DFSConfigKeys.DFS_JOURNALNODE_RPC_ADDRESS_KEY,
        "0.0.0.0:0");
    jn = new JournalNode();
    jn.setConf(conf);
    jn.start();
    journal = jn.getOrCreateJournal(JID);
    journal.format(FAKE_NSINFO);
    
    ch = new IPCLoggerChannel(conf, FAKE_NSINFO, JID, jn.getBoundIpcAddress());
  }
  
  @After
  public void teardown() throws Exception {
    jn.stop(0);
  }
  
  @Test
  public void testJournal() throws Exception {
    MetricsRecordBuilder metrics = MetricsAsserts.getMetrics(
        journal.getMetricsForTests().getName());
    MetricsAsserts.assertCounter("BatchesWritten", 0L, metrics);
    MetricsAsserts.assertCounter("BatchesWrittenWhileLagging", 0L, metrics);

    IPCLoggerChannel ch = new IPCLoggerChannel(
        conf, FAKE_NSINFO, JID, jn.getBoundIpcAddress());
    ch.newEpoch(1).get();
    ch.setEpoch(1);
    ch.startLogSegment(1).get();
    ch.sendEdits(1L, 1, 1, "hello".getBytes(Charsets.UTF_8)).get();
    
    metrics = MetricsAsserts.getMetrics(
        journal.getMetricsForTests().getName());
    MetricsAsserts.assertCounter("BatchesWritten", 1L, metrics);
    MetricsAsserts.assertCounter("BatchesWrittenWhileLagging", 0L, metrics);
  }
  
  
  @Test
  public void testReturnsSegmentInfoAtEpochTransition() throws Exception {
    ch.newEpoch(1).get();
    ch.setEpoch(1);
    ch.startLogSegment(1).get();
    ch.sendEdits(1L, 1, 2, QJMTestUtil.createTxnData(1, 2)).get();
    
    // Switch to a new epoch without closing earlier segment
    NewEpochResponseProto response = ch.newEpoch(2).get();
    ch.setEpoch(2);
    assertEquals(1, response.getLastSegmentTxId());
    
    ch.finalizeLogSegment(1, 2).get();
    
    // Switch to a new epoch after just closing the earlier segment.
    response = ch.newEpoch(3).get();
    ch.setEpoch(3);
    assertEquals(1, response.getLastSegmentTxId());
    
    // Start a segment but don't write anything, check newEpoch segment info
    ch.startLogSegment(3).get();
    response = ch.newEpoch(4).get();
    ch.setEpoch(4);
    // Because the new segment is empty, it is equivalent to not having
    // started writing it. Hence, we should return the prior segment txid.
    assertEquals(1, response.getLastSegmentTxId());
  }
  
  @Test
  public void testHttpServer() throws Exception {
    InetSocketAddress addr = jn.getBoundHttpAddress();
    assertTrue(addr.getPort() > 0);
    
    String urlRoot = "http://localhost:" + addr.getPort();
    
    // Check default servlets.
    String pageContents = DFSTestUtil.urlGet(new URL(urlRoot + "/jmx"));
    assertTrue("Bad contents: " + pageContents,
        pageContents.contains(
            "Hadoop:service=JournalNode,name=JvmMetrics"));
    
    // Check JSP page.
    pageContents = DFSTestUtil.urlGet(
        new URL(urlRoot + "/journalstatus.jsp"));
    assertTrue(pageContents.contains("JournalNode"));

    // Create some edits on server side
    byte[] EDITS_DATA = QJMTestUtil.createTxnData(1, 3);
    IPCLoggerChannel ch = new IPCLoggerChannel(
        conf, FAKE_NSINFO, JID, jn.getBoundIpcAddress());
    ch.newEpoch(1).get();
    ch.setEpoch(1);
    ch.startLogSegment(1).get();
    ch.sendEdits(1L, 1, 3, EDITS_DATA).get();
    ch.finalizeLogSegment(1, 3).get();

    // Attempt to retrieve via HTTP, ensure we get the data back
    // including the header we expected
    byte[] retrievedViaHttp = DFSTestUtil.urlGetBytes(new URL(urlRoot +
        "/getJournal?segmentTxId=1&jid=" + JID));
    byte[] expected = Bytes.concat(
            Ints.toByteArray(HdfsConstants.LAYOUT_VERSION),
            EDITS_DATA);

    assertArrayEquals(expected, retrievedViaHttp);
    
    // Attempt to fetch a non-existent file, check that we get an
    // error status code
    URL badUrl = new URL(urlRoot + "/getJournal?segmentTxId=12345&jid=" + JID);
    HttpURLConnection connection = (HttpURLConnection)badUrl.openConnection();
    try {
      assertEquals(404, connection.getResponseCode());
    } finally {
      connection.disconnect();
    }
  }

  /**
   * Test that the JournalNode performs correctly as a Paxos
   * <em>Acceptor</em> process.
   */
  @Test
  public void testAcceptRecoveryBehavior() throws Exception {
    // We need to run newEpoch() first, or else we have no way to distinguish
    // different proposals for the same decision.
    try {
      ch.prepareRecovery(1L).get();
      fail("Did not throw IllegalState when trying to run paxos without an epoch");
    } catch (ExecutionException ise) {
      GenericTestUtils.assertExceptionContains("bad epoch", ise);
    }
    
    ch.newEpoch(1).get();
    ch.setEpoch(1);
    
    // prepare() with no previously accepted value and no logs present
    PrepareRecoveryResponseProto prep = ch.prepareRecovery(1L).get();
    System.err.println("Prep: " + prep);
    assertFalse(prep.hasAcceptedInEpoch());
    assertFalse(prep.hasSegmentState());
    
    // Make a log segment, and prepare again -- this time should see the
    // segment existing.
    ch.startLogSegment(1L).get();
    ch.sendEdits(1L, 1L, 1, QJMTestUtil.createTxnData(1, 1)).get();

    prep = ch.prepareRecovery(1L).get();
    System.err.println("Prep: " + prep);
    assertFalse(prep.hasAcceptedInEpoch());
    assertTrue(prep.hasSegmentState());
    
    // accept() should save the accepted value in persistent storage
    // TODO: should be able to accept without a URL here
    ch.acceptRecovery(prep.getSegmentState(), new URL("file:///dev/null")).get();

    // So another prepare() call from a new epoch would return this value
    ch.newEpoch(2);
    ch.setEpoch(2);
    prep = ch.prepareRecovery(1L).get();
    assertEquals(1L, prep.getAcceptedInEpoch());
    assertEquals(1L, prep.getSegmentState().getEndTxId());
    
    // A prepare() or accept() call from an earlier epoch should now be rejected
    ch.setEpoch(1);
    try {
      ch.prepareRecovery(1L).get();
      fail("prepare from earlier epoch not rejected");
    } catch (ExecutionException ioe) {
      GenericTestUtils.assertExceptionContains(
          "epoch 1 is less than the last promised epoch 2",
          ioe);
    }
    try {
      ch.acceptRecovery(prep.getSegmentState(), new URL("file:///dev/null")).get();
      fail("accept from earlier epoch not rejected");
    } catch (ExecutionException ioe) {
      GenericTestUtils.assertExceptionContains(
          "epoch 1 is less than the last promised epoch 2",
          ioe);
    }
  }
  
  @Test
  public void testFailToStartWithBadConfig() throws Exception {
    Configuration conf = new Configuration();
    conf.set(DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY, "non-absolute-path");
    assertJNFailsToStart(conf, "should be an absolute path");
    
    // Existing file which is not a directory 
    conf.set(DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY, "/dev/null");
    assertJNFailsToStart(conf, "is not a directory");
    
    // Directory which cannot be created
    conf.set(DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY, "/proc/does-not-exist");
    assertJNFailsToStart(conf, "Could not create");

  }

  private static void assertJNFailsToStart(Configuration conf,
      String errString) {
    try {
      JournalNode jn = new JournalNode();
      jn.setConf(conf);
      jn.start();
    } catch (Exception e) {
      GenericTestUtils.assertExceptionContains(errString, e);
    }
  }
  
  // TODO:
  // - add test that checks formatting behavior
  // - add test that checks rejects newEpoch if nsinfo doesn't match
  
}