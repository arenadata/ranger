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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.hive.chained.hdfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hadoop.RangerHdfsAuthorizer;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.ResourceMapping;
import org.apache.ranger.plugin.model.ResourceMappingDiff;
import org.apache.ranger.plugin.model.ResourceMappingDiffs;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.plugin.util.JsonUtilsV2;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HDFS chained plugin gets the Hive policies and the {@code hive -> hdfs} mappings with their owners from Ranger
 * Admin over REST, as in a cluster, and applies {OWNER} in Hive policies to HDFS requests on table files.
 */
public class HdfsHiveChainedPluginOwnerTest {
    private static final String HIVE_SERVICE = "dev_hive";
    private static final long OWNER_POLICY_ID = 21;
    private static final String TABLE_LOCATION = "hdfs://nn1:8020/warehouse/db1.db/t1";
    private static final String TABLE_FILE = "/warehouse/db1.db/t1/000000_0";

    @TempDir
    private Path tempDir;

    private HttpServer admin;
    private final List<ResourceMappingDiff> publishedDiffs = new CopyOnWriteArrayList<>();
    private final List<String> requestedMappings = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void startAdmin() throws IOException {
        admin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        admin.createContext("/service/plugins/policies/download/" + HIVE_SERVICE, this::policies);
        admin.createContext("/service/resource-mappings/hive/hdfs/diffs/new", this::mappings);
        // roles and everything else did not change
        admin.createContext("/", exchange -> respond(exchange, 304, null));
        admin.start();
    }

    @AfterEach
    public void stopAdmin() {
        admin.stop(0);
    }

    @Test
    public void testTableOwnerReadsTheTableFiles() throws Exception {
        publish(create(1L, "alice"));
        HdfsHiveChainedPlugin plugin = startPlugin(true);

        RangerAccessResult owner = plugin.isAccessAllowed(request("alice"));
        assertTrue(owner.getIsAllowed());
        assertEquals(OWNER_POLICY_ID, owner.getPolicyId());
        assertEquals(HIVE_SERVICE, owner.getServiceName());

        assertFalse(plugin.isAccessAllowed(request("bob")).getIsAllowed());
    }

    @Test
    public void testOwnerChangeIsAppliedWithTheNextRefresh() throws Exception {
        publish(create(1L, "alice"));
        HdfsHiveChainedPlugin plugin = startPlugin(true);
        assertTrue(plugin.isAccessAllowed(request("alice")).getIsAllowed());

        publish(new ResourceMappingDiff(
            new ResourceMapping("hive.db1.t1", TABLE_LOCATION, "alice"),
            new ResourceMapping("hive.db1.t1", TABLE_LOCATION, "bob"),
            "TABLE", "UPDATE", 2L, "hive", "hdfs"));

        await(() -> plugin.isAccessAllowed(request("bob")).getIsAllowed(), "the new owner gets access");
        assertFalse(plugin.isAccessAllowed(request("alice")).getIsAllowed(), "the old owner loses access");
    }

    @Test
    public void testOwnerIsIgnoredWhenTheOptionIsOff() throws Exception {
        publish(create(1L, "alice"));
        HdfsHiveChainedPlugin plugin = startPlugin(false);

        assertFalse(plugin.isAccessAllowed(request("alice")).getIsAllowed());
    }

    /**
     * The plugin restarts with a mappings file written before RMM published owners: it is read, and the owners come
     * with the mappings RMM publishes again after a full sync.
     */
    @Test
    public void testMappingsFileWithoutOwners() throws Exception {
        Files.write(tempDir.resolve("mappings"), Arrays.asList(
            "1",
            "{\"path\":\"/warehouse/db1.db/t1\",\"name\":\"hive.db1.t1\",\"type\":\"TABLE\"}"),
            StandardCharsets.UTF_8);
        publish(create(1L, null));
        HdfsHiveChainedPlugin plugin = startPlugin(true);

        assertFalse(plugin.isAccessAllowed(request("alice")).getIsAllowed(), "the owner is not known yet");
        // with a mappings file the plugin does not fetch at start, it goes on from the version of the file
        await(() -> !requestedMappings.isEmpty(), "the plugin asks for the mapping changes");
        assertEquals("diffId=1", requestedMappings.get(0));

        publish(create(5L, "alice"));

        await(() -> plugin.isAccessAllowed(request("alice")).getIsAllowed(), "the owner comes with the full sync");
    }

    private HdfsHiveChainedPlugin startPlugin(boolean resourceOwnerEnabled) throws Exception {
        RangerPluginConfig config = new RangerPluginConfig("hdfs", "dev_hdfs", "hdfs", null, null, null);
        String prefix = config.getPropertyPrefix();
        config.set(prefix + ".policy.rest.url", "http://localhost:" + admin.getAddress().getPort());
        config.set(prefix + ".policy.cache.dir", tempDir.resolve("policy-cache").toString());
        config.set(prefix + ".policy.pollIntervalMs", "60000");
        config.set(prefix + ".hive.resource.mappings.file.location", tempDir.resolve("mappings").toString());
        config.set(prefix + ".hive.resource.mappings.refresh.interval.ms", "100");
        config.setBoolean(prefix + ".hive.resource.owner.enabled", resourceOwnerEnabled);

        HdfsHiveChainedPlugin plugin = new HdfsHiveChainedPlugin(new RangerBasePlugin(config), HIVE_SERVICE);
        plugin.init();
        return plugin;
    }

    private void publish(ResourceMappingDiff diff) {
        publishedDiffs.add(diff);
    }

    private static ResourceMappingDiff create(long id, String owner) {
        return new ResourceMappingDiff(new ResourceMapping("hive.db1.t1", TABLE_LOCATION, owner), null,
            "TABLE", "CREATE", id, "hive", "hdfs");
    }

    private static RangerAccessRequestImpl request(String user) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue(RangerHdfsAuthorizer.KEY_RESOURCE_PATH, TABLE_FILE);
        return new RangerAccessRequestImpl(resource, "read", user, Collections.emptySet(), Collections.emptySet());
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(50);
        }
    }

    private void policies(HttpExchange exchange) throws IOException {
        String query = String.valueOf(exchange.getRequestURI().getQuery());
        if (query.contains("lastKnownVersion=1&") || query.endsWith("lastKnownVersion=1")) {
            respond(exchange, 304, null);
            return;
        }
        try {
            respond(exchange, 200, JsonUtilsV2.objToJson(hivePolicies()));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void mappings(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        requestedMappings.add(String.valueOf(query));
        long diffId = query != null && query.startsWith("diffId=") ? Long.parseLong(query.substring("diffId=".length())) : -1L;
        List<ResourceMappingDiff> newer = publishedDiffs.stream()
            .filter(diff -> diff.getId() > diffId)
            .collect(Collectors.toList());
        try {
            respond(exchange, 200, JsonUtilsV2.objToJson(new ResourceMappingDiffs(new ArrayList<>(newer))));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static ServicePolicies hivePolicies() throws Exception {
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource("db1"));
        resources.put("table", new RangerPolicyResource("*"));
        resources.put("column", new RangerPolicyResource("*"));

        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(Collections.singletonList(RangerPolicyEngine.RESOURCE_OWNER));
        item.setAccesses(Collections.singletonList(new RangerPolicyItemAccess("select", true)));

        RangerPolicy policy = new RangerPolicy();
        policy.setId(OWNER_POLICY_ID);
        policy.setName("owner");
        policy.setService(HIVE_SERVICE);
        policy.setIsEnabled(true);
        policy.setResources(resources);
        policy.setPolicyItems(Collections.singletonList(item));

        ServicePolicies policies = new ServicePolicies();
        policies.setServiceName(HIVE_SERVICE);
        policies.setPolicyVersion(1L);
        policies.setServiceDef(EmbeddedServiceDefsUtil.instance()
            .getEmbeddedServiceDef(EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_HIVE_NAME));
        policies.setPolicies(Collections.singletonList(policy));
        return policies;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
