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

package org.apache.ranger.resource.mapper.hive;

import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_FILE_LOCATION_POSTFIX;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_REFRESH_INTERVAL_POSTFIX;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.RESOURCE_OWNER_ENABLED;
import static org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster.CATALOG;
import static org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster.database;
import static org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster.externalTable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.ranger.authorization.hadoop.RangerHdfsAuthorizer;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.hive.chained.hdfs.HdfsHiveChainedPlugin;
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
import org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster;
import org.apache.ranger.resource.mapper.testing.RangerAdminDatabase;
import org.apache.ranger.resource.mapper.testing.ResourceMappingManagerRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {OWNER} in Hive policies end to end: the owner of a table of a real Hive Metastore goes through RMM into the
 * resource mapping table of Ranger Admin, from there over REST into the HDFS chained plugin, and the plugin lets the
 * owner read the files of the table.
 *
 * <p>Ranger Admin is stood in for by an HTTP server: it serves the Hive policies and answers the resource mapping
 * requests of the plugin with the queries Ranger Admin runs on the same table. The JPA mapping of Ranger Admin over
 * this table is tested in security-admin.
 */
@Testcontainers(disabledWithoutDocker = true)
public class HiveOwnerContainerTest {
    private static final String HIVE_SERVICE = "dev_hive";
    private static final long OWNER_POLICY_ID = 21;
    private static final String DB = "e2e_db";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    /** the named queries XXMetastoreMappingDiff.getAllDiffs and getDiffsNewerThan of Ranger Admin */
    private static final String SELECT_DIFFS = "SELECT id, old_name, old_location, old_owner, new_name, new_location,"
        + " new_owner, entity_type, diff_type, source_service, target_service FROM x_resource_mapping_diff";
    private static final String ALL_DIFFS = SELECT_DIFFS + " WHERE source_service = ? AND target_service = ?";
    private static final String NEWER_DIFFS = SELECT_DIFFS + " WHERE id > ? AND source_service = ? AND target_service = ?";

    @TempDir
    private static Path tempDir;

    private static HiveMetastoreCluster cluster;
    private static RangerAdminDatabase adminDatabase;
    private static ResourceMappingManagerRunner rmm;
    private static IMetaStoreClient client;
    private static JdbcTemplate jdbc;
    private static HttpServer admin;
    private static HdfsHiveChainedPlugin plugin;

    @BeforeAll
    public static void start() throws Exception {
        cluster = HiveMetastoreCluster.start();
        adminDatabase = RangerAdminDatabase.start(RangerAdminDatabase.Type.POSTGRES, null);
        jdbc = adminDatabase.jdbc();
        client = cluster.newClient();

        client.createDatabase(database(DB, "dbowner"));
        // published with the snapshot RMM takes at start
        client.createTable(externalTable(DB, "snap_t", "alice", PrincipalType.USER));

        rmm = ResourceMappingManagerRunner.start(adminDatabase, cluster.getMetastoreUri(), Collections.emptyMap());
        admin = startAdmin();
        plugin = startPlugin();
    }

    @AfterAll
    public static void stop() throws Exception {
        if (admin != null) {
            admin.stop(0);
        }
        if (rmm != null) {
            rmm.close();
        }
        if (client != null) {
            client.close();
        }
        if (adminDatabase != null) {
            adminDatabase.close();
        }
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    public void testOwnerOfATableThatExistedBeforeRmmStarted() throws Exception {
        awaitReadAllowed("alice", "snap_t");

        assertFalse(isReadAllowed("bob", "snap_t"));
    }

    @Test
    public void testOwnerOfANewTableReadsItsFiles() throws Exception {
        client.createTable(externalTable(DB, "new_t", "alice", PrincipalType.USER));

        RangerAccessResult owner = awaitReadAllowed("alice", "new_t");
        assertEquals(OWNER_POLICY_ID, owner.getPolicyId());
        assertEquals(HIVE_SERVICE, owner.getServiceName());

        assertFalse(isReadAllowed("bob", "new_t"));
    }

    @Test
    public void testNewOwnerReadsTheFilesAfterTheOwnerChange() throws Exception {
        client.createTable(externalTable(DB, "alter_t", "alice", PrincipalType.USER));
        awaitReadAllowed("alice", "alter_t");

        Table table = client.getTable(CATALOG, DB, "alter_t");
        table.setOwner("carol");
        client.alter_table(CATALOG, DB, "alter_t", table);

        awaitReadAllowed("carol", "alter_t");
        assertFalse(isReadAllowed("alice", "alter_t"), "the old owner loses access");
    }

    private static HdfsHiveChainedPlugin startPlugin() throws Exception {
        RangerPluginConfig config = new RangerPluginConfig("hdfs", "dev_hdfs", "hdfs", null, null, null);
        String prefix = config.getPropertyPrefix();
        config.set(prefix + ".policy.rest.url", "http://localhost:" + admin.getAddress().getPort());
        config.set(prefix + ".policy.cache.dir", tempDir.resolve("policy-cache").toString());
        config.set(prefix + ".policy.pollIntervalMs", "60000");
        config.set(prefix + MAPPINGS_FILE_LOCATION_POSTFIX, tempDir.resolve("mappings").toString());
        config.set(prefix + MAPPINGS_REFRESH_INTERVAL_POSTFIX, "200");

        HdfsHiveChainedPlugin plugin = new HdfsHiveChainedPlugin(new RangerBasePlugin(config), HIVE_SERVICE);
        plugin.init();
        return plugin;
    }

    private static boolean isReadAllowed(String user, String table) {
        return plugin.isAccessAllowed(readRequest(user, table)).getIsAllowed();
    }

    private static RangerAccessResult awaitReadAllowed(String user, String table) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        RangerAccessResult result = plugin.isAccessAllowed(readRequest(user, table));
        while (!result.getIsAllowed()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(user + " is not allowed to read the files of " + table
                    + "; the published mappings: " + jdbc.queryForList(SELECT_DIFFS));
            }
            Thread.sleep(200);
            result = plugin.isAccessAllowed(readRequest(user, table));
        }
        return result;
    }

    /** a read of a file of the table, as the NameNode asks the plugin */
    private static RangerAccessRequestImpl readRequest(String user, String table) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue(RangerHdfsAuthorizer.KEY_RESOURCE_PATH, "/warehouse/" + DB + ".db/" + table + "/000000_0");
        return new RangerAccessRequestImpl(resource, "read", user, Collections.emptySet(), Collections.emptySet());
    }

    private static HttpServer startAdmin() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/service/plugins/policies/download/" + HIVE_SERVICE, HiveOwnerContainerTest::policies);
        server.createContext("/service/resource-mappings/hive/hdfs/diffs/new", HiveOwnerContainerTest::mappings);
        // roles and everything else did not change
        server.createContext("/", exchange -> respond(exchange, 304, null));
        server.start();
        return server;
    }

    private static void policies(HttpExchange exchange) throws IOException {
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

    /** as ResourceMappingsREST of Ranger Admin */
    private static void mappings(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        List<ResourceMappingDiff> diffs = query != null && query.startsWith("diffId=")
            ? jdbc.query(NEWER_DIFFS, HiveOwnerContainerTest::toDiff,
                Long.parseLong(query.substring("diffId=".length())), "hive", "hdfs")
            : jdbc.query(ALL_DIFFS, HiveOwnerContainerTest::toDiff, "hive", "hdfs");
        try {
            respond(exchange, 200, JsonUtilsV2.objToJson(new ResourceMappingDiffs(diffs)));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** as ResourceMappingMgr of Ranger Admin */
    private static ResourceMappingDiff toDiff(ResultSet row, int rowNumber) throws SQLException {
        return new ResourceMappingDiff(
            toMapping(row.getString("old_name"), row.getString("old_location"), row.getString("old_owner")),
            toMapping(row.getString("new_name"), row.getString("new_location"), row.getString("new_owner")),
            row.getString("entity_type"),
            row.getString("diff_type"),
            row.getLong("id"),
            row.getString("source_service"),
            row.getString("target_service"));
    }

    /** JPA reads no embedded mapping when all its columns are empty */
    private static ResourceMapping toMapping(String name, String location, String owner) {
        return name == null && location == null && owner == null ? null : new ResourceMapping(name, location, owner);
    }

    private static ServicePolicies hivePolicies() throws Exception {
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource(DB));
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
        policies.setServiceConfig(Collections.singletonMap(RESOURCE_OWNER_ENABLED, "true"));
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
