package org.apache.ranger.resource.mapper.hive.event.fetch;

import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.NewHiveSourceStateRecord.newStateRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import lombok.Data;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.CurrentNotificationEventId;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.junit.jupiter.api.Test;

class CompositeHiveMetastoreFetcherTest {
    @Test
    public void fetchWithIntermediateEvents() throws Exception {
        CurrentNotificationEventId eventIdBeforeSnapshot = new CurrentNotificationEventId();
        eventIdBeforeSnapshot.setEventId(3L);
        CurrentNotificationEventId eventIdAfterSnapshot = new CurrentNotificationEventId();
        eventIdAfterSnapshot.setEventId(5L);

        List<ResourceDiffStreamRecord> snapshotRecords = Arrays.asList(
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3)
        );

        List<ResourceDiffStreamRecord> eventRecords = Arrays.asList(
            new DummyRecord(4),
            new DummyRecord(5),
            new DummyRecord(6)
        );

        List<ResourceDiffStreamRecord> expectedRecords = Arrays.asList(
            newStateRecord(SNAPSHOT_STARTED),
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3),
            newStateRecord(SNAPSHOT_FINISHED),
            newStateRecord(INTERMEDIATE_EVENTS_STARTED),
            new DummyRecord(4),
            new DummyRecord(5),
            newStateRecord(INTERMEDIATE_EVENTS_FINISHED),
            newStateRecord(EVENTS_STARTED),
            new DummyRecord(6),
            ResourceDiffStreamRecord.END_OF_STREAM_RECORD
        );

        testMultiPhaseFetch(
            eventIdBeforeSnapshot,
            eventIdAfterSnapshot,
            snapshotRecords,
            eventRecords,
            expectedRecords
        );
    }

    @Test
    public void fetchWithoutIntermediateEvents() throws Exception {
        CurrentNotificationEventId eventIdBeforeSnapshot = new CurrentNotificationEventId();
        eventIdBeforeSnapshot.setEventId(3L);

        List<ResourceDiffStreamRecord> snapshotRecords = Arrays.asList(
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3)
        );

        List<ResourceDiffStreamRecord> eventRecords = Arrays.asList(
            new DummyRecord(4),
            new DummyRecord(5),
            new DummyRecord(6)
        );

        List<ResourceDiffStreamRecord> expectedRecords = Arrays.asList(
            newStateRecord(SNAPSHOT_STARTED),
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3),
            newStateRecord(SNAPSHOT_FINISHED),
            newStateRecord(EVENTS_STARTED),
            new DummyRecord(4),
            new DummyRecord(5),
            new DummyRecord(6),
            ResourceDiffStreamRecord.END_OF_STREAM_RECORD
        );

        testMultiPhaseFetch(
            eventIdBeforeSnapshot,
            eventIdBeforeSnapshot,
            snapshotRecords,
            eventRecords,
            expectedRecords
        );
    }

    @Test
    public void fetchDirectlyFromEventFetcher() throws Exception {
        List<ResourceDiffStreamRecord> eventRecords = Arrays.asList(
            new DummyRecord(3),
            new DummyRecord(4),
            new DummyRecord(5),
            new DummyRecord(6)
        );

        List<ResourceDiffStreamRecord> expectedRecords = Arrays.asList(
            newStateRecord(EVENTS_STARTED),
            new DummyRecord(4),
            new DummyRecord(5),
            new DummyRecord(6),
            ResourceDiffStreamRecord.END_OF_STREAM_RECORD
        );

        testSinglePhaseFetch(
            3L,
            eventRecords,
            expectedRecords
        );
    }

    private void testMultiPhaseFetch(
        CurrentNotificationEventId eventIdBeforeSnapshot,
        CurrentNotificationEventId eventIdAfterSnapshot,
        List<ResourceDiffStreamRecord> snapshotRecords,
        List<ResourceDiffStreamRecord> eventRecords,
        List<ResourceDiffStreamRecord> expectedRecords
    ) throws Exception {
        IMetaStoreClient metaStoreClient = mock(IMetaStoreClient.class);
        when(metaStoreClient.getCurrentNotificationEventId())
            .thenReturn(eventIdBeforeSnapshot, eventIdAfterSnapshot);

        try (CompositeHiveMetastoreFetcher fetcher = initFetcher(metaStoreClient, snapshotRecords, eventRecords)) {
            fetcher.multiPhaseFetch();
            BlockingQueue<ResourceDiffStreamRecord> outputQueue = fetcher.getOutputQueue();
            assertEquals(expectedRecords, new ArrayList<>(outputQueue));
        }
    }

    private void testSinglePhaseFetch(
        long fromEventId,
        List<ResourceDiffStreamRecord> eventRecords,
        List<ResourceDiffStreamRecord> expectedRecords) {
        IMetaStoreClient metaStoreClient = mock(IMetaStoreClient.class);

        try (CompositeHiveMetastoreFetcher fetcher = initFetcher(metaStoreClient, Collections.emptyList(),
            eventRecords)) {
            fetcher.fetchMetastoreEventsDirectly(fromEventId);
            BlockingQueue<ResourceDiffStreamRecord> outputQueue = fetcher.getOutputQueue();

            assertEquals(expectedRecords, new ArrayList<>(outputQueue));
        }
    }

    private CompositeHiveMetastoreFetcher initFetcher(
        IMetaStoreClient metaStoreClient,
        List<ResourceDiffStreamRecord> snapshotRecords,
        List<ResourceDiffStreamRecord> eventRecords) {

        return CompositeHiveMetastoreFetcher.builder()
            .metaStoreClient(metaStoreClient)
            .snapshotFetcher(new MockSnapshotFetcher(snapshotRecords))
            .eventFetcher(new MockEventFetcher(eventRecords))
            // we don't use executor in tests
            .executor(null)
            .authenticator(HiveAuthenticator.noOpAuthenticator())
            .eventBatchSize(1000)
            .build();
    }

    private static class MockSnapshotFetcher extends HiveMetastoreSnapshotFetcher {

        private final BlockingQueue<ResourceDiffStreamRecord> records;

        public MockSnapshotFetcher(List<ResourceDiffStreamRecord> records) {
            super(null, null, null, null, 1);
            this.records = new ArrayBlockingQueue<>(records.size() + 1);
            this.records.addAll(records);
            this.records.add(ResourceDiffStreamRecord.endOfStreamRecord());
        }

        @Override
        public BlockingQueue<ResourceDiffStreamRecord> pollAllAsync() {
            return records;
        }

        @Override
        public BlockingQueue<ResourceDiffStreamRecord> pollAsync(long fromEventId) {
            return records;
        }
    }

    private static class MockEventFetcher extends HiveMetastoreEventFetcher {
        private final BlockingQueue<ResourceDiffStreamRecord> records;

        public MockEventFetcher(List<ResourceDiffStreamRecord> records) {
            this(new ArrayBlockingQueue<>(records.size() + 1), null);
            this.records.addAll(records);
            this.records.add(ResourceDiffStreamRecord.endOfStreamRecord());
        }

        private MockEventFetcher(BlockingQueue<ResourceDiffStreamRecord> records, Long endEventId) {
            super(null, null, null, null, null, 0, 1, endEventId);
            this.records = records;
        }

        @Override
        public BlockingQueue<ResourceDiffStreamRecord> pollAllAsync() {
            return records;
        }

        @Override
        public BlockingQueue<ResourceDiffStreamRecord> pollAsync(long fromEventId) {
            BlockingQueue<ResourceDiffStreamRecord> queue = records.stream()
                .filter(DummyRecord.class::isInstance)
                .map(DummyRecord.class::cast)
                .filter(record -> record.id > fromEventId)
                .filter(record -> getEndEventId() == null || record.id <= getEndEventId())
                .collect(Collectors.toCollection(() -> new ArrayBlockingQueue<>(records.size() + 1)));
            queue.add(ResourceDiffStreamRecord.endOfStreamRecord());

            return queue;
        }

        @Override
        public HiveMetastoreEventFetcher toFiniteFetcher(long endEventId) {
            return new MockEventFetcher(records, endEventId);
        }

        @Override
        public void close() {
            // do nothing
        }
    }

    @Data
    private static class DummyRecord implements ResourceDiffStreamRecord {
        private final int id;
    }
}