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

package org.apache.ranger.resource.mapper.hive.event.fetch;

import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.HIVE_SERVICE;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.thirdparty.com.google.common.collect.Iterables;
import org.apache.ranger.resource.mapper.event.retry.RetryException;
import org.apache.ranger.resource.mapper.event.retry.RetrySupport;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.apache.thrift.TException;

@Slf4j
public class HiveMetastoreSnapshotFetcher extends BaseHiveMetastoreFetcher {
    public static final long SNAPSHOT_EVENT_ID = -1L;

    private final IMetaStoreClient metaStoreClient;
    private final ExecutorService executor;
    private final HiveAuthenticator authenticator;
    private final RetrySupport retrySupport;
    private final int eventBatchSize;

    @Getter
    private final BlockingQueue<ResourceDiffStreamRecord> outputQueue;
    private final AtomicBoolean pollStarted;

    @lombok.Builder(builderClassName = "Builder")
    public HiveMetastoreSnapshotFetcher(
        IMetaStoreClient metaStoreClient,
        ExecutorService executor,
        HiveAuthenticator authenticator,
        RetrySupport retrySupport,
        int eventBatchSize
    ) {
        this.metaStoreClient = metaStoreClient;
        this.executor = executor;
        this.eventBatchSize = eventBatchSize;
        this.outputQueue = new ArrayBlockingQueue<>(eventBatchSize);
        this.retrySupport = retrySupport;
        this.pollStarted = new AtomicBoolean(false);
        this.authenticator = authenticator;
    }

    @Override
    public BlockingQueue<ResourceDiffStreamRecord> pollAllAsync() throws Exception {
        return pollAsync(SNAPSHOT_EVENT_ID);
    }

    @Override
    public BlockingQueue<ResourceDiffStreamRecord> pollAsync(long fromEventId) throws Exception {
        if (pollStarted.compareAndSet(false, true)) {
            authenticator.login();
            executor.submit(() -> pollRecordsBatch(fromEventId));
        }
        return outputQueue;
    }

    @Override
    public String getServiceName() {
        return HIVE_SERVICE;
    }

    void pollRecordsBatch(long diffId) {
        try {
            retrySupport.withRetries(
                () -> authenticator.executeSecurely(() -> pollRecordsBatchAction(diffId))
            );
        } catch (RetryException retryException) {
            log.error("Exiting HiveMetastoreEventFetcher due to error", retryException);
            close();
        }
    }

    private void pollRecordsBatchAction(long diffId) {
        try {
            snapshotMetastore(diffId);
        } catch (Exception e) {
            throw new RuntimeException("Error polling records batch from Hive Metastore", e);
        }
    }

    private void snapshotMetastore(long diffId) throws TException {
        for (String catalog : metaStoreClient.getCatalogs()) {
            for (String dbName : metaStoreClient.getAllDatabases(catalog)) {
                Database database = metaStoreClient.getDatabase(dbName);
                outputQueue.add(toCreateDiff(catalog, database, diffId));

                List<String> allTables = metaStoreClient.getAllTables(catalog, dbName);

                for (List<String> tableNamesBatch : Iterables.partition(allTables, eventBatchSize)) {
                    for (Table table : metaStoreClient.getTableObjectsByName(catalog, dbName, tableNamesBatch)) {
                        String tableName = fullName(catalog, dbName, table.getTableName());

                        if (!isSupportedTable(tableName, table.getTableType())) {
                            continue;
                        }

                        outputQueue.add(toCreateDiff(table, tableName, diffId));
                    }
                }
            }
        }

        outputQueue.add(ResourceDiffStreamRecord.endOfStreamRecord());
    }

    private ResourceMappingDiff toCreateDiff(String catalog, Database database, long diffId) {
        log.debug("Saving new db from metastore: {}", database.getName());

        return MetastoreEntityDiffFactory.createEntity(
            fullName(catalog, database.getName()),
            HiveEntityType.DATABASE,
            database.getLocationUri(),
            diffId);
    }

    private ResourceMappingDiff toCreateDiff(Table table, String name, long diffId) {
        log.debug("Saving new table from metastore: {}", table.getTableName());

        return MetastoreEntityDiffFactory.createEntity(
            name,
            HiveEntityType.TABLE,
            table.getSd().getLocation(),
            diffId);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
        }

        outputQueue.add(ResourceDiffStreamRecord.endOfStreamRecord());
    }
}
