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

import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.STARROCKS_CATALOGS;
import static org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster.CATALOG;
import static org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster.database;
import static org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster.externalTable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.ranger.resource.mapper.testing.HiveMetastoreCluster;
import org.apache.ranger.resource.mapper.testing.RangerAdminDatabase;
import org.apache.ranger.resource.mapper.testing.ResourceMappingManagerRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RMM publishes the owners of the Hive databases and tables of a real Hive Metastore into the resource mapping table
 * of Ranger Admin: from the snapshot taken at start and from the notification events after it. The mappings are
 * derived for a StarRocks catalog too.
 */
@Testcontainers(disabledWithoutDocker = true)
public class HiveResourceMappingManagerOwnersContainerTest {
    private static final String DB = "own_db";
    private static final String HDFS = "hdfs";
    private static final String STARROCKS = "starrocks";
    private static final String STARROCKS_CATALOG = "sr_hive";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static HiveMetastoreCluster cluster;
    private static RangerAdminDatabase adminDatabase;
    private static ResourceMappingManagerRunner rmm;
    private static IMetaStoreClient client;
    private static JdbcTemplate jdbc;

    @BeforeAll
    public static void start() throws Exception {
        cluster = HiveMetastoreCluster.start();
        adminDatabase = RangerAdminDatabase.start(RangerAdminDatabase.Type.POSTGRES, null);
        jdbc = adminDatabase.jdbc();
        client = cluster.newClient();

        // the entities that exist before RMM starts come from its snapshot
        client.createDatabase(database(DB, "dbowner"));
        client.createTable(externalTable(DB, "snap_user", "alice", PrincipalType.USER));
        client.createTable(externalTable(DB, "snap_role", "admins", PrincipalType.ROLE));

        rmm = ResourceMappingManagerRunner.start(adminDatabase, cluster.getMetastoreUri(),
            Collections.singletonMap(STARROCKS_CATALOGS, STARROCKS_CATALOG));
    }

    @AfterAll
    public static void stop() throws Exception {
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
    public void testSnapshotPublishesUserOwners() {
        assertEquals("dbowner", awaitSingle("hive.own_db", HDFS).get("old_owner"));
        assertEquals("alice", awaitSingle(name("snap_user"), HDFS).get("old_owner"));
        assertNull(awaitSingle(name("snap_role"), HDFS).get("old_owner"), "a role does not own the table for {OWNER}");

        Map<String, Object> starRocks = awaitSingle(name("snap_user"), STARROCKS);
        assertEquals("sr_hive.own_db.snap_user", starRocks.get("old_location"));
        assertEquals("alice", starRocks.get("old_owner"));
    }

    @Test
    public void testNewTableIsPublishedWithItsOwner() throws Exception {
        client.createTable(externalTable(DB, "ev_create", "bob", PrincipalType.USER));

        Map<String, Object> created = awaitSingle(name("ev_create"), HDFS);
        assertEquals("CREATE", created.get("diff_type"));
        assertEquals("bob", created.get("old_owner"));
        assertEquals("bob", awaitSingle(name("ev_create"), STARROCKS).get("old_owner"));
    }

    @Test
    public void testOwnerChangeIsPublished() throws Exception {
        client.createTable(externalTable(DB, "ev_alter", "alice", PrincipalType.USER));
        awaitSingle(name("ev_alter"), HDFS);

        Table table = client.getTable(CATALOG, DB, "ev_alter");
        table.setOwner("carol");
        client.alter_table(CATALOG, DB, "ev_alter", table);

        Map<String, Object> update = awaitDiff(name("ev_alter"), HDFS, "UPDATE");
        assertEquals("alice", update.get("old_owner"));
        assertEquals("carol", update.get("new_owner"));
        assertEquals(update.get("old_location"), update.get("new_location"), "only the owner changed");
        assertEquals("carol", awaitDiff(name("ev_alter"), STARROCKS, "UPDATE").get("new_owner"));
    }

    @Test
    public void testRoleTakingTheTableOverRemovesTheOwner() throws Exception {
        client.createTable(externalTable(DB, "ev_role", "alice", PrincipalType.USER));
        awaitSingle(name("ev_role"), HDFS);

        Table table = client.getTable(CATALOG, DB, "ev_role");
        table.setOwner("admins");
        table.setOwnerType(PrincipalType.ROLE);
        client.alter_table(CATALOG, DB, "ev_role", table);

        Map<String, Object> update = awaitDiff(name("ev_role"), HDFS, "UPDATE");
        assertEquals("alice", update.get("old_owner"));
        assertNull(update.get("new_owner"));
    }

    /** properties, columns or statistics change the table, not its mapping */
    @Test
    public void testOtherChangesAreNotPublished() throws Exception {
        client.createTable(externalTable(DB, "ev_props", "alice", PrincipalType.USER));
        awaitSingle(name("ev_props"), HDFS);

        Table table = client.getTable(CATALOG, DB, "ev_props");
        table.putToParameters("comment", "changed");
        client.alter_table(CATALOG, DB, "ev_props", table);
        // the events are handled in order: once the next one is published, the alter has been handled
        client.createTable(externalTable(DB, "ev_props_marker", "alice", PrincipalType.USER));
        awaitSingle(name("ev_props_marker"), HDFS);

        assertEquals(1, diffs(name("ev_props"), HDFS).size());
    }

    @Test
    public void testDatabaseOwnerChangeIsPublished() throws Exception {
        client.createDatabase(database("own_db2", "dave"));
        assertEquals("dave", awaitSingle("hive.own_db2", HDFS).get("old_owner"));

        Database database = client.getDatabase(CATALOG, "own_db2");
        database.setOwnerName("erin");
        client.alterDatabase(CATALOG, "own_db2", database);

        Map<String, Object> update = awaitDiff("hive.own_db2", HDFS, "UPDATE");
        assertEquals("dave", update.get("old_owner"));
        assertEquals("erin", update.get("new_owner"));
    }

    private static String name(String table) {
        return CATALOG + "." + DB + "." + table;
    }

    private static List<Map<String, Object>> diffs(String name, String targetService) {
        return jdbc.queryForList(
            "SELECT diff_type, old_name, old_location, old_owner, new_name, new_location, new_owner"
                + " FROM x_resource_mapping_diff WHERE old_name = ? AND target_service = ? ORDER BY id",
            name, targetService);
    }

    private static Map<String, Object> awaitSingle(String name, String targetService) {
        return await(name, targetService, rows -> !rows.isEmpty()).get(0);
    }

    private static Map<String, Object> awaitDiff(String name, String targetService, String diffType) {
        List<Map<String, Object>> rows = await(name, targetService,
            found -> found.stream().anyMatch(row -> diffType.equals(row.get("diff_type"))));
        return rows.stream()
            .filter(row -> diffType.equals(row.get("diff_type")))
            .collect(Collectors.toList())
            .get(0);
    }

    private static List<Map<String, Object>> await(String name, String targetService,
                                                   Predicate<List<Map<String, Object>>> condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        List<Map<String, Object>> rows = diffs(name, targetService);
        while (!condition.test(rows)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("RMM did not publish the expected " + targetService + " mapping of " + name
                    + "; published: " + rows);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            rows = diffs(name, targetService);
        }
        return rows;
    }
}
