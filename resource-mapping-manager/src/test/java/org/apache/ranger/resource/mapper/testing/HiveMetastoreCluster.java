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

package org.apache.ranger.resource.mapper.testing;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A Hive Metastore in Docker that keeps its warehouse on HDFS, because RMM maps only HDFS and Ozone locations. The
 * metastore publishes notification events, which RMM reads after its snapshot.
 */
public final class HiveMetastoreCluster implements AutoCloseable {
    public static final String CATALOG = "hive";
    public static final String WAREHOUSE = "hdfs://namenode:8020/warehouse/";

    private static final DockerImageName HIVE_IMAGE = DockerImageName.parse("apache/hive:4.0.1");
    private static final int METASTORE_PORT = 9083;

    private final Network network;
    private final GenericContainer<?> namenode;
    private final GenericContainer<?> metastore;

    private HiveMetastoreCluster(Network network, GenericContainer<?> namenode, GenericContainer<?> metastore) {
        this.network = network;
        this.namenode = namenode;
        this.metastore = metastore;
    }

    public static HiveMetastoreCluster start() {
        Network network = Network.newNetwork();
        // the namenode of the Hadoop the Hive image ships: native on arm64 too, unlike the Hadoop images
        GenericContainer<?> namenode = new GenericContainer<>(HIVE_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("namenode")
            .withEnv("HADOOP_CONF_DIR", "/hadoop-conf")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/core-site.xml"), "/hadoop-conf/core-site.xml")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/hdfs-site.xml"), "/hadoop-conf/hdfs-site.xml")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c",
                "/opt/hadoop/bin/hdfs namenode -format -force -nonInteractive && exec /opt/hadoop/bin/hdfs namenode"))
            .waitingFor(Wait.forLogMessage(".*IPC Server listener on 8020: starting.*", 1)
                .withStartupTimeout(Duration.ofMinutes(3)));
        GenericContainer<?> metastore = new GenericContainer<>(HIVE_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("metastore")
            .withEnv("SERVICE_NAME", "metastore")
            .withEnv("HIVE_CUSTOM_CONF_DIR", "/hive-custom-conf")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/hive-site.xml"), "/hive-custom-conf/hive-site.xml")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/core-site.xml"), "/hive-custom-conf/core-site.xml")
            .withCopyToContainer(MountableFile.forClasspathResource("hms-it/hdfs-site.xml"), "/hive-custom-conf/hdfs-site.xml")
            .withExposedPorts(METASTORE_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));

        HiveMetastoreCluster cluster = new HiveMetastoreCluster(network, namenode, metastore);
        try {
            namenode.start();
            metastore.start();
        } catch (RuntimeException e) {
            cluster.close();
            throw e;
        }
        return cluster;
    }

    public String getMetastoreUri() {
        return "thrift://" + metastore.getHost() + ":" + metastore.getMappedPort(METASTORE_PORT);
    }

    public IMetaStoreClient newClient() throws MetaException {
        Configuration conf = new Configuration(false);
        conf.set("hive.metastore.uris", getMetastoreUri());
        return new HiveMetaStoreClient(conf);
    }

    public static Database database(String name, String owner) {
        Database database = new Database(name, null, WAREHOUSE + name + ".db", new HashMap<>());
        database.setCatalogName(CATALOG);
        database.setOwnerName(owner);
        database.setOwnerType(PrincipalType.USER);
        return database;
    }

    /**
     * @return an external table at the default location of its database
     */
    public static Table externalTable(String database, String name, String owner, PrincipalType ownerType) {
        SerDeInfo serDe = new SerDeInfo();
        serDe.setSerializationLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serDe.setParameters(new HashMap<>());

        StorageDescriptor sd = new StorageDescriptor();
        sd.setCols(Collections.singletonList(new FieldSchema("id", "int", null)));
        sd.setLocation(WAREHOUSE + database + ".db/" + name);
        sd.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        sd.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
        sd.setSerdeInfo(serDe);

        Map<String, String> parameters = new HashMap<>();
        parameters.put("EXTERNAL", "TRUE");

        Table table = new Table();
        table.setCatName(CATALOG);
        table.setDbName(database);
        table.setTableName(name);
        table.setTableType(TableType.EXTERNAL_TABLE.name());
        table.setParameters(parameters);
        table.setPartitionKeys(Collections.emptyList());
        table.setSd(sd);
        table.setOwner(owner);
        table.setOwnerType(ownerType);
        return table;
    }

    @Override
    public void close() {
        metastore.close();
        namenode.close();
        network.close();
    }
}
