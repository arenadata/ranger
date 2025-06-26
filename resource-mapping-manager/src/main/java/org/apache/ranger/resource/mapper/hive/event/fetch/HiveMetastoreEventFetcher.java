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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.messaging.AlterDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.AlterTableMessage;
import org.apache.hadoop.hive.metastore.messaging.CreateDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.CreateTableMessage;
import org.apache.hadoop.hive.metastore.messaging.DropDatabaseMessage;
import org.apache.hadoop.hive.metastore.messaging.DropTableMessage;
import org.apache.hadoop.hive.metastore.messaging.MessageDeserializer;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.apache.ranger.resource.mapper.event.retry.RetrySupport;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.hive.model.HiveEventType;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

@Slf4j
public class HiveMetastoreEventFetcher extends BaseHiveMetastoreFetcher {
    public static final long INITIAL_DIFF_ID = 0L;
    public static final Set<String> SUPPORTED_TABLE_TYPES = Sets.newHashSet(
        TableType.EXTERNAL_TABLE.name(),
        TableType.MANAGED_TABLE.name()
    );

    private final IMetaStoreClient metaStoreClient;
    private final MessageDeserializer eventMessageDeserializer;
    private final ScheduledExecutorService executor;
    private final HiveAuthenticator authenticator;
    private final RetrySupport retrySupport;
    private final int eventBatchSize;
    private final long fetchPeriodMs;

    @Getter
    private final BlockingQueue<ResourceDiffStreamRecord> outputQueue;
    private final AtomicBoolean pollStarted;
    private final AtomicBoolean pollFinished;

    @Getter
    private volatile long lastHandledEventId;
    private final Long endEventId;

    @lombok.Builder(
        builderClassName = "Builder",
        toBuilder = true
    )
    public HiveMetastoreEventFetcher(
        IMetaStoreClient metaStoreClient,
        MessageDeserializer eventMessageDeserializer,
        ScheduledExecutorService executor,
        HiveAuthenticator authenticator,
        RetrySupport retrySupport,
        long fetchPeriodMs,
        int eventBatchSize,
        Long endEventId
    ) {
        this.metaStoreClient = metaStoreClient;
        this.executor = executor;
        this.eventMessageDeserializer = eventMessageDeserializer;
        this.fetchPeriodMs = fetchPeriodMs;
        this.eventBatchSize = eventBatchSize;
        this.outputQueue = new ArrayBlockingQueue<>(eventBatchSize);
        this.retrySupport = retrySupport;
        this.pollStarted = new AtomicBoolean(false);
        this.pollFinished = new AtomicBoolean(false);
        this.authenticator = authenticator;
        this.endEventId = endEventId;
    }

    @Override
    public BlockingQueue<ResourceDiffStreamRecord> pollAllAsync() throws Exception {
        return pollAsync(INITIAL_DIFF_ID);
    }

    @Override
    public BlockingQueue<ResourceDiffStreamRecord> pollAsync(long fromEventId) throws Exception {
        if (pollStarted.compareAndSet(false, true)) {
            authenticator.login();
            lastHandledEventId = fromEventId;
            executor.scheduleAtFixedRate(this::pollRecordsBatch,
                0L, fetchPeriodMs, TimeUnit.MILLISECONDS);
        }
        return outputQueue;
    }

    @Override
    public String getServiceName() {
        return HIVE_SERVICE;
    }

    void pollRecordsBatch() {
        try {
            retrySupport.withRetries(
                () -> authenticator.executeSecurely(this::pollRecordsBatchAction)
            );

            if (pollFinished.get()) {
                close();
            }
        } catch (Exception exception) {
            log.error("Exiting HiveMetastoreEventFetcher due to error", exception);
            close();
        }
    }

    private void pollRecordsBatchAction() {
        try {
            List<NotificationEvent> events = metaStoreClient.getNextNotification(
                lastHandledEventId,
                eventBatchSize,
                this::isSupportedEvent
            ).getEvents();

            if (CollectionUtils.isEmpty(events)) {
                log.debug("No new records from HMS to handle");
                return;
            }

            for (NotificationEvent event : events) {
                if (endEventId != null && event.getEventId() > endEventId) {
                    log.info("Stopping polling on event {}", event);
                    pollFinished.set(true);
                    return;
                }

                handle(event);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error polling records batch from Hive Metastore", e);
        }
    }

    private void handle(NotificationEvent event) {
        try {
            switch (HiveEventType.valueOf(event.getEventType())) {
                case CREATE_TABLE:
                    handleCreateTable(event);
                    break;
                case DROP_TABLE:
                    handleDropTable(event);
                    break;
                case ALTER_TABLE:
                    handleAlterTable(event);
                    break;
                case CREATE_DATABASE:
                    handleCreateDb(event);
                    break;
                case DROP_DATABASE:
                    handleDropDb(event);
                    break;
                case ALTER_DATABASE:
                    handleAlterDb(event);
                    break;
            }

            // it's guaranteed that events are sorted by eventId in the batch
            lastHandledEventId = event.getEventId();
        } catch (Exception e) {
            log.error("Error handling hive event", e);
        }
    }

    private void handleCreateTable(NotificationEvent event) throws Exception {
        log.debug("Handle create table event: {}", event);
        String tableName = fullTableName(event);

        CreateTableMessage createTableMessage = eventMessageDeserializer
            .getCreateTableMessage(event.getMessage());

        if (!isSupportedTable(tableName, createTableMessage.getTableType())) {
            return;
        }

        ResourceMappingDiff createEntityDiff = MetastoreEntityDiffFactory.createEntity(
            tableName,
            HiveEntityType.TABLE,
            createTableMessage.getTableObj().getSd().getLocation(),
            event.getEventId());

        outputQueue.put(createEntityDiff);
    }

    private void handleCreateDb(NotificationEvent event) throws Exception {
        log.debug("Handle create db event: {}", event);

        CreateDatabaseMessage createDatabaseMessage = eventMessageDeserializer
            .getCreateDatabaseMessage(event.getMessage());

        ResourceMappingDiff createEntityDiff = MetastoreEntityDiffFactory.createEntity(
            fullDbName(event),
            HiveEntityType.DATABASE,
            createDatabaseMessage.getDatabaseObject().getLocationUri(),
            event.getEventId());

        outputQueue.put(createEntityDiff);
    }

    private void handleDropTable(NotificationEvent event) throws Exception {
        log.debug("Handle drop table event: {}", event);
        String tableName = fullTableName(event);

        DropTableMessage dropTableMessage = eventMessageDeserializer
            .getDropTableMessage(event.getMessage());

        if (!isSupportedTable(tableName, dropTableMessage.getTableType())) {
            return;
        }

        ResourceMappingDiff dropEntityDiff = MetastoreEntityDiffFactory.dropEntity(
            tableName,
            dropTableMessage.getTableObj().getSd().getLocation(),
            HiveEntityType.TABLE,
            event.getEventId());

        outputQueue.put(dropEntityDiff);
    }

    private void handleDropDb(NotificationEvent event) throws Exception {
        log.debug("Handle drop db event: {}", event);

        DropDatabaseMessage dropDatabaseMessage = eventMessageDeserializer
            .getDropDatabaseMessage(event.getMessage());

        ResourceMappingDiff dropEntityDiff = MetastoreEntityDiffFactory.dropEntity(
            fullDbName(event),
            dropDatabaseMessage.getDatabaseObject().getLocationUri(),
            HiveEntityType.DATABASE,
            event.getEventId());

        outputQueue.put(dropEntityDiff);
    }

    private void handleAlterTable(NotificationEvent event) throws Exception {
        AlterTableMessage alterTableMessage = eventMessageDeserializer
            .getAlterTableMessage(event.getMessage());

        String oldFullName = fullTableName(event);
        if (!isSupportedTable(oldFullName, alterTableMessage.getTableType())) {
            return;
        }

        Table tableObjBefore = alterTableMessage.getTableObjBefore();
        Table tableObjAfter = alterTableMessage.getTableObjAfter();

        String newFullName = fullName(event.getCatName(), event.getDbName(), tableObjAfter.getTableName());

        if (!oldFullName.equals(newFullName)
            || !tableObjBefore.getSd().getLocation().equals(tableObjAfter.getSd().getLocation())) {
            log.debug("Handle alter table event: {}", event);

            ResourceMappingDiff resourceMappingDiff = MetastoreEntityDiffFactory.updateEntity(
                new ResourceMapping(
                    oldFullName,
                    tableObjBefore.getSd().getLocation()),
                new ResourceMapping(
                    newFullName,
                    tableObjAfter.getSd().getLocation()),
                HiveEntityType.TABLE,
                event.getEventId()
            );

            outputQueue.put(resourceMappingDiff);
        }
    }

    private void handleAlterDb(NotificationEvent event) throws Exception {
        AlterDatabaseMessage alterDatabaseMessage = eventMessageDeserializer
            .getAlterDatabaseMessage(event.getMessage());

        Database dbObjBefore = alterDatabaseMessage.getDbObjBefore();
        Database dbObjAfter = alterDatabaseMessage.getDbObjAfter();

        if (!dbObjBefore.getName().equals(dbObjAfter.getName())
            || !dbObjBefore.getLocationUri().equals(dbObjAfter.getLocationUri())) {
            log.debug("Handle alter db event: {}", event);

            ResourceMappingDiff resourceMappingDiff = MetastoreEntityDiffFactory.updateEntity(
                new ResourceMapping(
                    fullDbName(event),
                    dbObjBefore.getLocationUri()),
                new ResourceMapping(
                    fullName(event.getCatName(), dbObjAfter.getName()),
                    dbObjAfter.getLocationUri()),
                HiveEntityType.DATABASE,
                event.getEventId()
            );

            outputQueue.put(resourceMappingDiff);
        }
    }

    private boolean isSupportedEvent(NotificationEvent event) {
        return HiveEventType.isSupported(event.getEventType());
    }

    private String fullTableName(NotificationEvent event) {
        return fullName(event.getCatName(), event.getDbName(), event.getTableName());
    }

    private String fullDbName(NotificationEvent event) {
        return fullName(event.getCatName(), event.getDbName());
    }

    public HiveMetastoreEventFetcher toFiniteFetcher(long endEventId) {
        return toBuilder().endEventId(endEventId).build();
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
        }

        try {
            metaStoreClient.close();
        } catch (Exception e) {
            log.error("Error closing Hive Metastore client", e);
        }

        outputQueue.add(ResourceDiffStreamRecord.endOfStreamRecord());
    }
}
