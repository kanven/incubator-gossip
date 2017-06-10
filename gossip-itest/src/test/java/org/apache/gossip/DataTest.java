/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gossip;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.gossip.crdt.GrowOnlyCounter;
import org.apache.gossip.crdt.GrowOnlySet;
import org.apache.gossip.crdt.LWWSet;
import org.apache.gossip.crdt.OrSet;
import org.apache.gossip.crdt.PNCounter;
import org.apache.gossip.manager.GossipManager;
import org.apache.gossip.manager.GossipManagerBuilder;
import org.apache.gossip.model.PerNodeDataMessage;
import org.apache.gossip.model.SharedDataMessage;
import org.junit.Test;

import io.teknek.tunit.TUnit;

public class DataTest extends AbstractIntegrationBase {

  private String orSetKey = "cror";
  private String lwwSetKey = "crlww";
  private String gCounterKey = "crdtgc";
  private String pnCounterKey = "crdtpn";

  @Test
  public void dataTest() throws InterruptedException, UnknownHostException, URISyntaxException {
    GossipSettings settings = new GossipSettings();
    settings.setPersistRingState(false);
    settings.setPersistDataState(false);
    String cluster = UUID.randomUUID().toString();
    int seedNodes = 1;
    List<Member> startupMembers = new ArrayList<>();
    for (int i = 1; i < seedNodes + 1; ++i) {
      URI uri = new URI("udp://" + "127.0.0.1" + ":" + (50000 + i));
      startupMembers.add(new RemoteMember(cluster, uri, i + ""));
    }
    final List<GossipManager> clients = new ArrayList<>();
    final int clusterMembers = 2;
    for (int i = 1; i < clusterMembers + 1; ++i) {
      URI uri = new URI("udp://" + "127.0.0.1" + ":" + (50000 + i));
      GossipManager gossipService = GossipManagerBuilder
              .newBuilder()
              .cluster(cluster).uri(uri)
              .id(i + "")
              .gossipMembers(startupMembers)
              .gossipSettings(settings).build();
      clients.add(gossipService);
      gossipService.init();
      register(gossipService);
    }
    TUnit.assertThat(() -> {
      int total = 0;
      for (int i = 0; i < clusterMembers; ++i) {
        total += clients.get(i).getLiveMembers().size();
      }
      return total;
    }).afterWaitingAtMost(20, TimeUnit.SECONDS).isEqualTo(2);
    clients.get(0).gossipPerNodeData(generatePerNodeMsg("a", "b"));
    clients.get(0).gossipSharedData(generateSharedMsg("a", "c"));

    TUnit.assertThat(() -> {
      PerNodeDataMessage x = clients.get(1).findPerNodeGossipData(1 + "", "a");
      if (x == null)
        return "";
      else
        return x.getPayload();
    }).afterWaitingAtMost(20, TimeUnit.SECONDS).isEqualTo("b");

    TUnit.assertThat(() -> {
      SharedDataMessage x = clients.get(1).findSharedGossipData("a");
      if (x == null)
        return "";
      else
        return x.getPayload();
    }).afterWaitingAtMost(20, TimeUnit.SECONDS).isEqualTo("c");

    givenDifferentDatumsInSet(clients);
    assertThatListIsMerged(clients);

    testOrSet(clients);
    testLWWSet(clients);

    testGrowOnlyCounter(clients);
    testPNCounter(clients);

    for (int i = 0; i < clusterMembers; ++i) {
      clients.get(i).shutdown();
    }
  }

  private void testOrSet(final List<GossipManager> clients) {
    // populate
    clients.get(0).merge(generateSharedMsg(orSetKey, new OrSet<>("1", "2")));
    clients.get(1).merge(generateSharedMsg(orSetKey, new OrSet<>("3", "4")));

    // assert merge
    assertMerged(clients.get(0), orSetKey, new OrSet<>("1", "2", "3", "4").value());
    assertMerged(clients.get(1), orSetKey, new OrSet<>("1", "2", "3", "4").value());

    // drop element
    @SuppressWarnings("unchecked")
    OrSet<String> o = (OrSet<String>) clients.get(0).findCrdt(orSetKey);
    OrSet<String> o2 = new OrSet<>(o, new OrSet.Builder<String>().remove("3"));
    clients.get(0).merge(generateSharedMsg(orSetKey, o2));

    // assert deletion
    assertMerged(clients.get(0), orSetKey, new OrSet<>("1", "2", "4").value());
    assertMerged(clients.get(1), orSetKey, new OrSet<>("1", "2", "4").value());
  }

  private void testLWWSet(final List<GossipManager> clients) {
    // populate
    clients.get(0).merge(generateSharedMsg(lwwSetKey, new LWWSet<>("1", "2")));
    clients.get(1).merge(generateSharedMsg(lwwSetKey, new LWWSet<>("3", "4")));

    // assert merge
    assertMerged(clients.get(0), lwwSetKey, new LWWSet<>("1", "2", "3", "4").value());
    assertMerged(clients.get(1), lwwSetKey, new LWWSet<>("1", "2", "3", "4").value());

    // drop element
    @SuppressWarnings("unchecked")
    LWWSet<String> lww = (LWWSet<String>) clients.get(0).findCrdt(lwwSetKey);
    clients.get(0).merge(generateSharedMsg(lwwSetKey, lww.remove("3")));

    // assert deletion
    assertMerged(clients.get(0), lwwSetKey, new OrSet<>("1", "2", "4").value());
    assertMerged(clients.get(1), lwwSetKey, new OrSet<>("1", "2", "4").value());
  }

  private void testGrowOnlyCounter(List<GossipManager> clients) {
    givenDifferentIncrement(clients);
    assertThatCountIsUpdated(clients, 3);
    givenIncreaseOther(clients);
    assertThatCountIsUpdated(clients, 7);
  }

  private void testPNCounter(List<GossipManager> clients) {
    givenPNCounter(clients);
    assertThatPNCounterSettlesAt(clients, 0);
    int[] delta1 = { 2, 3 };
    givenPNCounterUpdate(clients, delta1);
    assertThatPNCounterSettlesAt(clients, 5);
    int[] delta2 = { -3, 5 };
    givenPNCounterUpdate(clients, delta2);
    assertThatPNCounterSettlesAt(clients, 7);
    int[] delta3 = { 1, 1 };
    givenPNCounterUpdate(clients, delta3);
    assertThatPNCounterSettlesAt(clients, 9);
    int[] delta4 = { 1, -7 };
    givenPNCounterUpdate(clients, delta4);
    assertThatPNCounterSettlesAt(clients, 3);
  }

  private void givenDifferentIncrement(final List<GossipManager> clients) {
    Object payload = new GrowOnlyCounter(new GrowOnlyCounter.Builder(clients.get(0)).increment(1L));
    clients.get(0).merge(generateSharedMsg(gCounterKey, payload));
    payload = new GrowOnlyCounter(new GrowOnlyCounter.Builder(clients.get(1)).increment(2L));
    clients.get(1).merge(generateSharedMsg(gCounterKey, payload));
  }

  private void givenIncreaseOther(final List<GossipManager> clients) {
    GrowOnlyCounter gc = (GrowOnlyCounter) clients.get(1).findCrdt(gCounterKey);
    GrowOnlyCounter gc2 = new GrowOnlyCounter(gc,
            new GrowOnlyCounter.Builder(clients.get(1)).increment(4L));

    clients.get(1).merge(generateSharedMsg(gCounterKey, gc2));
  }

  private void assertMerged(final GossipManager client, String key, final Set<String> expected) {
    TUnit.assertThat(() -> client.findCrdt(key).value()).afterWaitingAtMost(10, TimeUnit.SECONDS)
            .isEqualTo(expected);
  }

  private void givenDifferentDatumsInSet(final List<GossipManager> clients) {
    clients.get(0).merge(CrdtMessage("1"));
    clients.get(1).merge(CrdtMessage("2"));
  }

  private void assertThatCountIsUpdated(final List<GossipManager> clients, long finalCount) {
    TUnit.assertThat(() -> clients.get(0).findCrdt(gCounterKey))
            .afterWaitingAtMost(10, TimeUnit.SECONDS).isEqualTo(new GrowOnlyCounter(
                    new GrowOnlyCounter.Builder(clients.get(0)).increment(finalCount)));
  }

  private void assertThatListIsMerged(final List<GossipManager> clients) {
    TUnit.assertThat(() -> clients.get(0).findCrdt("cr")).afterWaitingAtMost(10, TimeUnit.SECONDS)
            .isEqualTo(new GrowOnlySet<>(Arrays.asList("1", "2")));
  }
  
  private void givenPNCounter(List<GossipManager> clients) {
    {
      SharedDataMessage d = new SharedDataMessage();
      d.setKey(pnCounterKey);
      d.setPayload(new PNCounter(new PNCounter.Builder(clients.get(0))));
      d.setExpireAt(Long.MAX_VALUE);
      d.setTimestamp(System.currentTimeMillis());
      clients.get(0).merge(d);
    }
    {
      SharedDataMessage d = new SharedDataMessage();
      d.setKey(pnCounterKey);
      d.setPayload(new PNCounter(new PNCounter.Builder(clients.get(1))));
      d.setExpireAt(Long.MAX_VALUE);
      d.setTimestamp(System.currentTimeMillis());
      clients.get(1).merge(d);
    }
  }

  private void givenPNCounterUpdate(List<GossipManager> clients, int[] deltaArray) {
    int clientIndex = 0;
    for (int delta: deltaArray) {
      PNCounter c = (PNCounter) clients.get(clientIndex).findCrdt(pnCounterKey);
      c = new PNCounter(c, new PNCounter.Builder(clients.get(clientIndex)).increment(((long)delta)));
      SharedDataMessage d = new SharedDataMessage();
      d.setKey(pnCounterKey);
      d.setPayload(c);
      d.setExpireAt(Long.MAX_VALUE);
      d.setTimestamp(System.currentTimeMillis());
      clients.get(clientIndex).merge(d);
      clientIndex = (clientIndex + 1) % clients.size();
    }
  }

  private void assertThatPNCounterSettlesAt(List<GossipManager> clients, long expectedValue) {
    for (GossipManager client: clients) {
      TUnit.assertThat(() -> {
        long value = 0;
        Object o = client.findCrdt(pnCounterKey);
        if (o != null) {
          PNCounter c = (PNCounter)o;
          value = c.value();
        }
        return value;
      }).afterWaitingAtMost(10, TimeUnit.SECONDS)
              .isEqualTo(expectedValue);
    }
  }

  private SharedDataMessage CrdtMessage(String item) {
    return generateSharedMsg("cr", new GrowOnlySet<>(Arrays.asList(item)));
  }

  private PerNodeDataMessage generatePerNodeMsg(String key, Object payload) {
    PerNodeDataMessage g = new PerNodeDataMessage();
    g.setExpireAt(Long.MAX_VALUE);
    g.setKey(key);
    g.setPayload(payload);
    g.setTimestamp(System.currentTimeMillis());
    return g;
  }

  private SharedDataMessage generateSharedMsg(String key, Object payload) {
    SharedDataMessage d = new SharedDataMessage();
    d.setKey(key);
    d.setPayload(payload);
    d.setExpireAt(Long.MAX_VALUE);
    d.setTimestamp(System.currentTimeMillis());
    return d;
  }
}