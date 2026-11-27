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

import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_DRIVER_CLASSNAME;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_JDBC_URL;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_PASSWORD;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_USERNAME;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FETCH_PERIOD_MS;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.STARROCKS_CATALOGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.ranger.resource.mapper.event.ResourceDiffHandler;
import org.apache.ranger.resource.mapper.hive.config.HiveResourceMappingManagerConfig;
import org.apache.ranger.resource.mapper.testing.RangerAdminDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * RMM publishes the owners of the Hive databases and tables of a real Hive Metastore into the resource mapping table
 * of Ranger Admin: from the snapshot taken at start and from the notification events after it. The metastore keeps
 * its warehouse on HDFS, because RMM maps only HDFS and Ozone locations, and the mappings are derived for a StarRocks
 * catalog too.
 */
@Testcontainers(disabledWithoutDocker = true)
public class HiveResourceMappingManagerOwnersContainerTest {
    private static final DockerImageName HIVE_IMAGE = DockerImageName.parse("apache/hive:4.0.1");
    private static final int METASTORE_PORT = 9083;
    private static final String CATALOG = "hive";
    private static final String DB = "own_db";
    private static final String WAREHOUSE = "hdfs://namenode:8020/warehouse/";
    private static final String HDFS = "hdfs";
    private static final String STARROCKS = "starrocks";
    private static final String STARROCKS_CATALOG = "sr_hive";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static Network network;
    private static GenericContainer<?> namenode;
    private static GenericContainer<?> metastore;
    private static RangerAdminDatabase adminDatabase;
    private static HikariDataSource rmmDataSource;
    private static ResourceDiffHandler rmm;
    private static ExecutorService rmmExecutor;
    private static IMetaStoreClient client;
    private static JdbcTemplate jdbc;

    @BeforeAll
    public static void start() throws Exception {
        network = Network.newNetwork();
        // the namenode of the Hadoop the Hive image ships: native on arm64 too, unlike the Hadoop images
        namenode = new GenericContainer<>(HIVE_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("namenode")
            .withEnv("HADOOP_CONF_DIR", "/hadoop-conf")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/core-site.xml"), "/hadoop-conf/core-site.xml")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/hdfs-site.xml"), "/hadoop-conf/hdfs-site.xml")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c",
                "/opt/hadoop/bin/hdfs namenode -format -force -nonInteractive && exec /opt/hadoop/bin/hdfs namenode"))
            .waitingFor(Wait.forLogMessage(".*IPC Server listener on 8020: starting.*", 1)
                .withStartupTimeout(Duration.ofMinutes(3)));
        namenode.start();

        metastore = new GenericContainer<>(HIVE_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("metastore")
            .withEnv("SERVICE_NAME", "metastore")
            .withEnv("HIVE_CUSTOM_CONF_DIR", "/hive-custom-conf")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/hive-site.xml"), "/hive-custom-conf/hive-site.xml")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/core-site.xml"), "/hive-custom-conf/core-site.xml")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/hdfs-site.xml"), "/hive-custom-conf/hdfs-site.xml")
            .withExposedPorts(METASTORE_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));
        metastore.start();

        adminDatabase = RangerAdminDatabase.start(RangerAdminDatabase.Type.POSTGRES, null);
        jdbc = adminDatabase.jdbc();

        Configuration clientConf = new Configuration(false);
        clientConf.set("hive.metastore.uris", metastoreUri());
        client = new HiveMetaStoreClient(clientConf);

        // the entities that exist before RMM starts come from its snapshot
        client.createDatabase(database(DB, "dbowner"));
        client.createTable(table("snap_user", "alice", PrincipalType.USER));
        client.createTable(table("snap_role", "admins", PrincipalType.ROLE));

        startRmm();
    }

    @AfterAll
    public static void stop() throws Exception {
        if (rmm != null) {
            rmm.close();
        }
        if (rmmExecutor != null) {
            rmmExecutor.shutdownNow();
            rmmExecutor.awaitTermination(30, TimeUnit.SECONDS);
        }
        if (rmmDataSource != null) {
            rmmDataSource.close();
        }
        if (client != null) {
            client.close();
        }
        if (adminDatabase != null) {
            adminDatabase.close();
        }
        if (metastore != null) {
            metastore.close();
        }
        if (namenode != null) {
            namenode.close();
        }
        if (network != null) {
            network.close();
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
        client.createTable(table("ev_create", "bob", PrincipalType.USER));

        Map<String, Object> created = awaitSingle(name("ev_create"), HDFS);
        assertEquals("CREATE", created.get("diff_type"));
        assertEquals("bob", created.get("old_owner"));
        assertEquals("bob", awaitSingle(name("ev_create"), STARROCKS).get("old_owner"));
    }

    @Test
    public void testOwnerChangeIsPublished() throws Exception {
        client.createTable(table("ev_alter", "alice", PrincipalType.USER));
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
        client.createTable(table("ev_role", "alice", PrincipalType.USER));
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
        client.createTable(table("ev_props", "alice", PrincipalType.USER));
        awaitSingle(name("ev_props"), HDFS);

        Table table = client.getTable(CATALOG, DB, "ev_props");
        table.putToParameters("comment", "changed");
        client.alter_table(CATALOG, DB, "ev_props", table);
        // the events are handled in order: once the next one is published, the alter has been handled
        client.createTable(table("ev_props_marker", "alice", PrincipalType.USER));
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

    private static void startRmm() throws Exception {
        HiveResourceMappingManagerConfig config = new HiveResourceMappingManagerConfig();
        config.set(DB_DRIVER_CLASSNAME, adminDatabase.getDriverClassName());
        config.set(DB_JDBC_URL, adminDatabase.getJdbcUrl());
        config.set(DB_USERNAME, adminDatabase.getUsername());
        config.set(DB_PASSWORD, adminDatabase.getPassword());
        config.set("hive.metastore.uris", metastoreUri());
        config.set(HMS_FETCH_PERIOD_MS, "200");
        config.set(STARROCKS_CATALOGS, STARROCKS_CATALOG);

        rmmDataSource = new HikariDataSource(config.getDbConfig());
        rmm = buildEventHandler(config, rmmDataSource);
        rmmExecutor = Executors.newSingleThreadExecutor();
        rmmExecutor.submit(() -> {
            rmm.start(false);
            return null;
        });
    }

    /**
     * Builds RMM as {@link HiveResourceMappingManager#run} does. {@code run} is not used: it blocks the thread and
     * exits the JVM when the handling fails, e.g. when the containers stop at the end of the test.
     */
    private static ResourceDiffHandler buildEventHandler(HiveResourceMappingManagerConfig config, DataSource dataSource)
        throws Exception {
        Method buildEventHandler = HiveResourceMappingManager.class.getDeclaredMethod(
            "buildEventHandler", HiveResourceMappingManagerConfig.class, DataSource.class);
        buildEventHandler.setAccessible(true);
        return (ResourceDiffHandler) buildEventHandler.invoke(new HiveResourceMappingManager(), config, dataSource);
    }

    private static String metastoreUri() {
        return "thrift://" + metastore.getHost() + ":" + metastore.getMappedPort(METASTORE_PORT);
    }

    private static String name(String table) {
        return CATALOG + "." + DB + "." + table;
    }

    private static Database database(String name, String owner) {
        Database database = new Database(name, null, WAREHOUSE + name + ".db", new HashMap<>());
        database.setCatalogName(CATALOG);
        database.setOwnerName(owner);
        database.setOwnerType(PrincipalType.USER);
        return database;
    }

    private static Table table(String name, String owner, PrincipalType ownerType) {
        SerDeInfo serDe = new SerDeInfo();
        serDe.setSerializationLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serDe.setParameters(new HashMap<>());

        StorageDescriptor sd = new StorageDescriptor();
        sd.setCols(Collections.singletonList(new FieldSchema("id", "int", null)));
        sd.setLocation(WAREHOUSE + DB + ".db/" + name);
        sd.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        sd.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
        sd.setSerdeInfo(serDe);

        Map<String, String> parameters = new HashMap<>();
        parameters.put("EXTERNAL", "TRUE");

        Table table = new Table();
        table.setCatName(CATALOG);
        table.setDbName(DB);
        table.setTableName(name);
        table.setTableType(TableType.EXTERNAL_TABLE.name());
        table.setParameters(parameters);
        table.setPartitionKeys(Collections.emptyList());
        table.setSd(sd);
        table.setOwner(owner);
        table.setOwnerType(ownerType);
        return table;
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
