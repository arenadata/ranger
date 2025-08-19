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

package org.apache.ranger.resource.mapper.hive.event;

import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.NewHiveSourceStateRecord.newStateRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import lombok.Data;
import org.apache.ranger.resource.mapper.event.ResourceDiffCollector;
import org.apache.ranger.resource.mapper.event.retry.PolicyBasedRetrySupport;
import org.apache.ranger.resource.mapper.event.retry.RetryException;
import org.apache.ranger.resource.mapper.event.retry.RetryPolicyFactory;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CompositeHiveResourceDiffCollectorTest {
    private MockTransactionManager transactionManager;
    private MockIntermediateEventResolver intermediateEventResolver;
    private MockResourceDiffCollector mockDiffCollectorDelegate;

    private CompositeHiveResourceDiffCollector diffCollector;

    @BeforeEach
    public void setUp() {
        transactionManager = new MockTransactionManager();
        intermediateEventResolver = new MockIntermediateEventResolver();
        mockDiffCollectorDelegate = new MockResourceDiffCollector();

        diffCollector = CompositeHiveResourceDiffCollector.builder()
            .delegate(mockDiffCollectorDelegate)
            .intermediateEventsResolver(intermediateEventResolver)
            .transactionManager(transactionManager)
            .retrySupport(new PolicyBasedRetrySupport(
                RetryPolicyFactory.NO_RETRIES_POLICY, Thread::sleep))
            .build();
    }

    @Test
    public void testHandleSnapshotRecordChainWithIntermediateEvents() throws Exception {
        List<ResourceDiffStreamRecord> records = Arrays.asList(
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
            new DummyRecord(6)
        );

        List<ResourceDiffStreamRecord> expectedIntermediateRecords = Arrays.asList(
            new DummyRecord(4),
            new DummyRecord(5)
        );

        List<ResourceDiffStreamRecord> expectedDelegateRecords = Arrays.asList(
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3),
            new DummyRecord(6)
        );

        testHandleRecordChain(
            records,
            expectedIntermediateRecords,
            expectedDelegateRecords
        );
        assertEquals(1, transactionManager.isCommited.size());
    }

    @Test
    public void testHandleSnapshotRecordChainWithoutIntermediateEvents() throws Exception {
        List<ResourceDiffStreamRecord> records = Arrays.asList(
            newStateRecord(SNAPSHOT_STARTED),
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3),
            newStateRecord(SNAPSHOT_FINISHED),
            newStateRecord(EVENTS_STARTED),
            new DummyRecord(6)
        );

        List<ResourceDiffStreamRecord> expectedDelegateRecords = Arrays.asList(
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3),
            new DummyRecord(6)
        );

        testHandleRecordChain(
            records,
            Collections.emptyList(),
            expectedDelegateRecords
        );
        assertEquals(1, transactionManager.isCommited.size());
    }

    @Test
    public void testHandleStreamingRecordChainWithoutIntermediateEvents() throws Exception {
        List<ResourceDiffStreamRecord> records = Arrays.asList(
            newStateRecord(EVENTS_STARTED),
            new DummyRecord(1),
            new DummyRecord(22),
            new DummyRecord(3333),
            new DummyRecord(6)
        );

        List<ResourceDiffStreamRecord> expectedDelegateRecords = Arrays.asList(
            new DummyRecord(1),
            new DummyRecord(22),
            new DummyRecord(3333),
            new DummyRecord(6)
        );

        testHandleRecordChain(
            records,
            Collections.emptyList(),
            expectedDelegateRecords
        );
    }

    @Test
    public void testRollbackTxIfSnapshotHandlerFails() {
        List<ResourceDiffStreamRecord> records = Arrays.asList(
            newStateRecord(SNAPSHOT_STARTED),
            new DummyRecord(1),
            new FailingRecord()
        );

        checkTxRollback(records);
    }

    @Test
    public void testRollbackTxIfIntermediateEventResolverFails() {
        List<ResourceDiffStreamRecord> records = Arrays.asList(
            newStateRecord(SNAPSHOT_STARTED),
            new DummyRecord(1),
            new DummyRecord(2),
            new DummyRecord(3),
            newStateRecord(SNAPSHOT_FINISHED),
            newStateRecord(INTERMEDIATE_EVENTS_STARTED),
            new DummyRecord(4),
            new DummyRecord(5),
            new FailingRecord()
        );

        checkTxRollback(records);
    }

    private void checkTxRollback(List<ResourceDiffStreamRecord> records) {
        assertThrows(RetryException.class, () -> {
            for (ResourceDiffStreamRecord record : records) {
                diffCollector.handle(record);
            }
        });

        assertTrue(transactionManager.isCommited.isEmpty());
        assertEquals(1, transactionManager.isRollbacked.size());
    }

    private void testHandleRecordChain(List<ResourceDiffStreamRecord> records,
                                       List<ResourceDiffStreamRecord> expectedIntermediateRecords,
                                       List<ResourceDiffStreamRecord> expectedDelegateRecords) throws Exception {
        for (ResourceDiffStreamRecord record : records) {
            diffCollector.handle(record);
        }

        assertEquals(expectedIntermediateRecords, intermediateEventResolver.handledRecords);
        assertEquals(expectedDelegateRecords, mockDiffCollectorDelegate.handledRecords);
        assertTrue(transactionManager.isRollbacked.isEmpty());
    }

    private static class MockResourceDiffCollector implements ResourceDiffCollector {
        private final List<ResourceDiffStreamRecord> handledRecords = new ArrayList<>();

        @Override
        public void collect(BlockingQueue<ResourceDiffStreamRecord> diffQueue) {
            // not used in tests
        }

        @Override
        public void handle(ResourceDiffStreamRecord record) {
            if (record instanceof FailingRecord) {
                throw new IllegalArgumentException("FailingRecord");
            }

            handledRecords.add(record);
        }
    }

    private static class MockIntermediateEventResolver implements HiveIntermediateEventsResolver {

        private final List<ResourceDiffStreamRecord> handledRecords = new ArrayList<>();

        @Override
        public void handle(ResourceDiffStreamRecord record) {
            if (record instanceof FailingRecord) {
                throw new IllegalArgumentException("FailingRecord");
            }

            handledRecords.add(record);
        }

        @Override
        public void flush() {

        }
    }

    private static class MockTransactionManager implements PlatformTransactionManager {
        private final Map<TransactionStatus, Boolean> isCommited = new HashMap<>();
        private final Map<TransactionStatus, Boolean> isRollbacked = new HashMap<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            isCommited.put(status, true);
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            isRollbacked.put(status, true);
        }
    }

    @Data
    private static class DummyRecord implements ResourceDiffStreamRecord {
        private final int id;
    }

    @Data
    private static class FailingRecord implements ResourceDiffStreamRecord {
        // no content
    }
}