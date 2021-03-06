/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery.zen;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNode.Role;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.discovery.zen.ping.ZenPing;
import org.elasticsearch.discovery.zen.ping.ZenPingService;
import org.elasticsearch.discovery.zen.publish.PublishClusterStateActionTests.AssertingAckListener;
import org.elasticsearch.discovery.zen.publish.PublishClusterStateActionTests.MockNode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.elasticsearch.discovery.zen.ZenDiscovery.shouldIgnoreOrRejectNewClusterState;
import static org.elasticsearch.discovery.zen.elect.ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING;
import static org.elasticsearch.discovery.zen.publish.PublishClusterStateActionTests.createMockNode;
import static org.elasticsearch.test.ClusterServiceUtils.createClusterService;
import static org.elasticsearch.test.ClusterServiceUtils.setState;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 */
public class ZenDiscoveryUnitTests extends ESTestCase {

    public void testShouldIgnoreNewClusterState() {
        ClusterName clusterName = new ClusterName("abc");

        DiscoveryNodes.Builder currentNodes = DiscoveryNodes.builder();
        currentNodes.masterNodeId("a").add(new DiscoveryNode("a", LocalTransportAddress.buildUnique(), emptyMap(), emptySet(), Version.CURRENT));
        DiscoveryNodes.Builder newNodes = DiscoveryNodes.builder();
        newNodes.masterNodeId("a").add(new DiscoveryNode("a", LocalTransportAddress.buildUnique(), emptyMap(), emptySet(), Version.CURRENT));

        ClusterState.Builder currentState = ClusterState.builder(clusterName);
        currentState.nodes(currentNodes);
        ClusterState.Builder newState = ClusterState.builder(clusterName);
        newState.nodes(newNodes);

        currentState.version(2);
        newState.version(1);
        assertTrue("should ignore, because new state's version is lower to current state's version", shouldIgnoreOrRejectNewClusterState(logger, currentState.build(), newState.build()));
        currentState.version(1);
        newState.version(1);
        assertTrue("should ignore, because new state's version is equal to current state's version", shouldIgnoreOrRejectNewClusterState(logger, currentState.build(), newState.build()));
        currentState.version(1);
        newState.version(2);
        assertFalse("should not ignore, because new state's version is higher to current state's version", shouldIgnoreOrRejectNewClusterState(logger, currentState.build(), newState.build()));

        currentNodes = DiscoveryNodes.builder();
        currentNodes.masterNodeId("b").add(new DiscoveryNode("b", LocalTransportAddress.buildUnique(), emptyMap(), emptySet(), Version.CURRENT));
        ;
        // version isn't taken into account, so randomize it to ensure this.
        if (randomBoolean()) {
            currentState.version(2);
            newState.version(1);
        } else {
            currentState.version(1);
            newState.version(2);
        }
        currentState.nodes(currentNodes);
        try {
            shouldIgnoreOrRejectNewClusterState(logger, currentState.build(), newState.build());
            fail("should ignore, because current state's master is not equal to new state's master");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), containsString("cluster state from a different master than the current one, rejecting"));
        }

        currentNodes = DiscoveryNodes.builder();
        currentNodes.masterNodeId(null);
        currentState.nodes(currentNodes);
        // version isn't taken into account, so randomize it to ensure this.
        if (randomBoolean()) {
            currentState.version(2);
            newState.version(1);
        } else {
            currentState.version(1);
            newState.version(2);
        }
        assertFalse("should not ignore, because current state doesn't have a master", shouldIgnoreOrRejectNewClusterState(logger, currentState.build(), newState.build()));
    }

    public void testFilterNonMasterPingResponse() {
        ArrayList<ZenPing.PingResponse> responses = new ArrayList<>();
        ArrayList<DiscoveryNode> masterNodes = new ArrayList<>();
        ArrayList<DiscoveryNode> allNodes = new ArrayList<>();
        for (int i = randomIntBetween(10, 20); i >= 0; i--) {
            Set<Role> roles = new HashSet<>(randomSubsetOf(Arrays.asList(Role.values())));
            DiscoveryNode node = new DiscoveryNode("node_" + i, "id_" + i, LocalTransportAddress.buildUnique(), Collections.emptyMap(),
                    roles, Version.CURRENT);
            responses.add(new ZenPing.PingResponse(node, randomBoolean() ? null : node, new ClusterName("test"), randomBoolean()));
            allNodes.add(node);
            if (node.isMasterNode()) {
                masterNodes.add(node);
            }
        }

        boolean ignore = randomBoolean();
        List<ZenPing.PingResponse> filtered = ZenDiscovery.filterPingResponses(
                responses.toArray(new ZenPing.PingResponse[responses.size()]), ignore, logger);
        final List<DiscoveryNode> filteredNodes = filtered.stream().map(ZenPing.PingResponse::node).collect(Collectors.toList());
        if (ignore) {
            assertThat(filteredNodes, equalTo(masterNodes));
        } else {
            assertThat(filteredNodes, equalTo(allNodes));
        }
    }

    public void testNodesUpdatedAfterClusterStatePublished() throws Exception {
        ThreadPool threadPool = new TestThreadPool(getClass().getName());
        // randomly make minimum_master_nodes a value higher than we have nodes for, so it will force failure
        int minMasterNodes = randomBoolean() ? 3 : 1;
        Settings settings = Settings.builder()
                                .put(DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING.getKey(), Integer.toString(minMasterNodes)).build();

        Map<String, MockNode> nodes = new HashMap<>();
        ZenDiscovery zenDiscovery = null;
        ClusterService clusterService = null;
        try {
            Set<DiscoveryNode> expectedFDNodes = null;
            // create master node and its mocked up services
            MockNode master = createMockNode("master", settings, null, threadPool, logger, nodes).setAsMaster();
            ClusterState state = master.clusterState; // initial cluster state

            // build the zen discovery and cluster service
            clusterService = createClusterService(threadPool, master.discoveryNode);
            setState(clusterService, state);
            zenDiscovery = buildZenDiscovery(settings, master, clusterService, threadPool);

            // a new cluster state with a new discovery node (we will test if the cluster state
            // was updated by the presence of this node in NodesFaultDetection)
            MockNode newNode = createMockNode("new_node", settings, null, threadPool, logger, nodes);
            ClusterState newState = ClusterState.builder(state).incrementVersion().nodes(
                DiscoveryNodes.builder(state.nodes()).add(newNode.discoveryNode).masterNodeId(master.discoveryNode.getId())
            ).build();

            try {
                // publishing a new cluster state
                ClusterChangedEvent clusterChangedEvent = new ClusterChangedEvent("testing", newState, state);
                AssertingAckListener listener = new AssertingAckListener(newState.nodes().getSize() - 1);
                expectedFDNodes = zenDiscovery.getFaultDetectionNodes();
                zenDiscovery.publish(clusterChangedEvent, listener);
                listener.await(1, TimeUnit.HOURS);
                // publish was a success, update expected FD nodes based on new cluster state
                expectedFDNodes = fdNodesForState(newState, master.discoveryNode);
            } catch (Discovery.FailedToCommitClusterStateException e) {
                // not successful, so expectedFDNodes above should remain what it was originally assigned
                assertEquals(3, minMasterNodes); // ensure min master nodes is the higher value, otherwise we shouldn't fail
            }

            assertEquals(expectedFDNodes, zenDiscovery.getFaultDetectionNodes());
        } finally {
            // clean close of transport service and publish action for each node
            zenDiscovery.close();
            clusterService.close();
            for (MockNode curNode : nodes.values()) {
                curNode.action.close();
                curNode.service.close();
            }
            terminate(threadPool);
        }
    }

    private ZenDiscovery buildZenDiscovery(Settings settings, MockNode master, ClusterService clusterService, ThreadPool threadPool) {
        ClusterSettings clusterSettings = new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        ZenPingService zenPingService = new ZenPingService(settings, Collections.emptySet());
        ElectMasterService electMasterService = new ElectMasterService(settings);
        ZenDiscovery zenDiscovery = new ZenDiscovery(settings, threadPool, master.service, clusterService,
                                                        clusterSettings, zenPingService, electMasterService);
        zenDiscovery.start();
        return zenDiscovery;
    }

    private Set<DiscoveryNode> fdNodesForState(ClusterState clusterState, DiscoveryNode localNode) {
        final Set<DiscoveryNode> discoveryNodes = new HashSet<>();
        clusterState.getNodes().getNodes().valuesIt().forEachRemaining(discoveryNode -> {
            // the local node isn't part of the nodes that are pinged (don't ping ourselves)
            if (discoveryNode.getId().equals(localNode.getId()) == false) {
                discoveryNodes.add(discoveryNode);
            }
        });
        return discoveryNodes;
    }
}
