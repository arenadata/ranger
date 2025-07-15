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

import static org.apache.ranger.resource.mapper.hive.model.NewHiveSourceStateRecord.newStateRecord;
import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.HIVE_SERVICE;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_STARTED;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;

@Slf4j
public class CompositeHiveMetastoreFetcher extends BaseHiveMetastoreFetcher {

    private final IMetaStoreClient metaStoreClient;
    private final HiveMetastoreSnapshotFetcher snapshotFetcher;
    private final HiveMetastoreEventFetcher eventFetcher;
    private final ExecutorService executor;
    private final HiveAuthenticator authenticator;

    @Getter
    private final BlockingQueue<ResourceDiffStreamRecord> outputQueue;
    private final AtomicBoolean pollStarted;

    @lombok.Builder(builderClassName = "Builder")
    public CompositeHiveMetastoreFetcher(
        IMetaStoreClient metaStoreClient,
        HiveMetastoreSnapshotFetcher snapshotFetcher,
        HiveMetastoreEventFetcher eventFetcher,
        ExecutorService executor,
        HiveAuthenticator authenticator,
        int eventBatchSize
    ) {
        this.metaStoreClient = metaStoreClient;
        this.snapshotFetcher = snapshotFetcher;
        this.eventFetcher = eventFetcher;
        this.executor = executor;
        this.outputQueue = new ArrayBlockingQueue<>(eventBatchSize);
        this.pollStarted = new AtomicBoolean(false);
        this.authenticator = authenticator;
    }

    @Override
    public BlockingQueue<ResourceDiffStreamRecord> pollAllAsync() throws Exception {
        if (pollStarted.compareAndSet(false, true)) {
            log.info("Start composite hive metastore event fetcher");
            authenticator.login();
            executor.submit(this::multiPhaseFetch);
        }
        return outputQueue;
    }

    @Override
    public BlockingQueue<ResourceDiffStreamRecord> pollAsync(long fromEventId) throws Exception {
        if (pollStarted.compareAndSet(false, true)) {
            log.info("Start simple hive metastore event fetcher");
            authenticator.login();
            executor.submit(() -> fetchMetastoreEventsDirectly(fromEventId));
        }

        return outputQueue;
    }

    @Override
    public String getServiceName() {
        return HIVE_SERVICE;
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

    void fetchMetastoreEventsDirectly(long fromEventId) {
        try {
            outputQueue.add(newStateRecord(EVENTS_STARTED));

            log.info("Start fetching Hive events using event fetcher from id {}", fromEventId);
            pollRecords(eventFetcher, fromEventId);
        } catch (Exception retryException) {
            log.error("Exiting HiveMetastoreEventFetcher due to error", retryException);
            close();
        } finally {
            outputQueue.add(ResourceDiffStreamRecord.endOfStreamRecord());
        }
    }

    void multiPhaseFetch() {
        try {
            // 1. Snapshot phase
            log.info("Start fetching Hive entities using snapshot fetcher");
            outputQueue.add(newStateRecord(SNAPSHOT_STARTED));

            long eventIdBeforeSnapshot = metaStoreClient.getCurrentNotificationEventId().getEventId();
            pollRecords(snapshotFetcher, eventIdBeforeSnapshot);
            long eventIdAfterSnapshot = metaStoreClient.getCurrentNotificationEventId().getEventId();

            outputQueue.add(newStateRecord(SNAPSHOT_FINISHED));
            log.info("Hive entities initial fetch successfully finished");

            // 2. Optional intermediate events phase
            if (eventIdBeforeSnapshot != eventIdAfterSnapshot) {
                log.info("Start resolving missed Hive entity diffs using conflict resolver:" +
                    " events from {} to {}", eventIdBeforeSnapshot, eventIdAfterSnapshot);
                resolveUnhandledEvents(eventIdBeforeSnapshot, eventIdAfterSnapshot);
            }

            // 3. Hive metastore events phase
            log.info("Start fetching Hive entity diffs using event fetcher from id {}", eventIdAfterSnapshot);

            outputQueue.add(newStateRecord(EVENTS_STARTED));
            pollRecords(eventFetcher, eventIdAfterSnapshot);
        } catch (Exception retryException) {
            log.error("Exiting HiveMetastoreEventFetcher due to error", retryException);
            close();
        } finally {
            outputQueue.add(ResourceDiffStreamRecord.endOfStreamRecord());
        }
    }

    private void resolveUnhandledEvents(long eventIdBeforeSnapshot,
                                        long eventIdAfterSnapshot) throws Exception {
        outputQueue.add(newStateRecord(INTERMEDIATE_EVENTS_STARTED));

        HiveMetastoreEventFetcher unhandledEventsFetcher = eventFetcher.toFiniteFetcher(eventIdAfterSnapshot);
        pollRecords(unhandledEventsFetcher, eventIdBeforeSnapshot);

        outputQueue.add(newStateRecord(INTERMEDIATE_EVENTS_FINISHED));
    }

    private void pollRecords(
        BaseHiveMetastoreFetcher fetcher,
        long fromEventId) throws Exception {
        try {
            BlockingQueue<ResourceDiffStreamRecord> recordQueue = fetcher.pollAsync(fromEventId);
            ResourceDiffStreamRecord event;
            do {
                event = recordQueue.take();
                if (!event.isLastRecord()) {
                    outputQueue.add(event);
                }
            } while (!event.isLastRecord());
        } catch (InterruptedException e) {
            outputQueue.add(ResourceDiffStreamRecord.endOfStreamRecord());
            log.warn("Error polling records", e);
        } finally {
            fetcher.close();
        }
    }
}
