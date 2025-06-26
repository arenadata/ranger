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

import static org.apache.ranger.resource.mapper.hive.event.fetch.HiveMetastoreEventFetcher.SUPPORTED_TABLE_TYPES;
import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.DEFAULT_LOCATION_SCHEME;
import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.HIVE_SERVICE;
import static org.apache.ranger.resource.mapper.hive.event.NotificationEventFactory.newAlterDbEvent;
import static org.apache.ranger.resource.mapper.hive.event.NotificationEventFactory.newAlterTableEvent;
import static org.apache.ranger.resource.mapper.hive.event.NotificationEventFactory.newCreateDbEvent;
import static org.apache.ranger.resource.mapper.hive.event.NotificationEventFactory.newCreateTableEvent;
import static org.apache.ranger.resource.mapper.hive.event.NotificationEventFactory.newDropDbEvent;
import static org.apache.ranger.resource.mapper.hive.event.NotificationEventFactory.newDropTableEvent;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.CREATE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.DELETE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.UPDATE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityType.DATABASE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityType.TABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.messaging.json.JSONMessageDeserializer;
import org.apache.ranger.resource.mapper.event.retry.PolicyBasedRetrySupport;
import org.apache.ranger.resource.mapper.event.retry.RetryPolicyFactory;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.apache.thrift.TException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

class HiveMetastoreEventFetcherTest {
    private static final int SUPPORTED_TABLE_TYPES_COUNT = SUPPORTED_TABLE_TYPES.size();
    private static final int SUPPORTED_TABLE_OPERATIONS_COUNT = 3;

    private MetastoreEventsHolder eventsHolder;

    private HiveMetastoreEventFetcher eventFetcher;

    @BeforeEach
    public void setUp() throws TException {
        IMetaStoreClient metaStoreClient = mock(IMetaStoreClient.class);
        when(metaStoreClient.getNextNotification(anyLong(), anyInt(), any()))
            .then(invocation -> getEvents(eventsHolder, invocation));

        eventFetcher = HiveMetastoreEventFetcher.builder()
            .metaStoreClient(metaStoreClient)
            .eventMessageDeserializer(new JSONMessageDeserializer())
            // we don't use executor in tests
            .fetchPeriodMs(-1)
            .eventBatchSize(10000)
            .authenticator(HiveAuthenticator.noOpAuthenticator())
            .retrySupport(new PolicyBasedRetrySupport(
                RetryPolicyFactory.NO_RETRIES_POLICY, Thread::sleep))
            .build();

        eventsHolder = new MetastoreEventsHolder();
    }

    @AfterEach
    public void cleanUp() {
        eventFetcher.close();
    }

    @Test
    public void testHandleEvents() {

        List<NotificationEvent> events = getDefaultTestEvents();
        eventsHolder.addEvents(events);

        List<ResourceDiffStreamRecord> expectedRecords = Arrays.asList(
            diff(1L, DATABASE, CREATE, "default.newdb", "/newdb"),
            diff(2L, TABLE, CREATE, "default.db.table1", "/out/table1"),
            diff(3L, TABLE, CREATE, "default.db.table2", "/db/table2"),
            updateDiff(5L, DATABASE,
                new ResourceMapping("default.newdb", "/newdb"),
                new ResourceMapping("default.newestdb", "/newestdb")),
            diff(6L, TABLE, DELETE, "default.db.table2", "/db/table2"),
            updateDiff(7L, DATABASE,
                new ResourceMapping("default.newestdb", "/newestdb"),
                new ResourceMapping("default.newestdb", "/other/location")),
            diff(8L, DATABASE, DELETE, "default.newestdb", "/other/location"),
            updateDiff(9L, TABLE,
                new ResourceMapping("default.db.table2", "/db/table2"),
                new ResourceMapping("default.db.table3", "/db/table3")),
            updateDiff(11L, TABLE,
                new ResourceMapping("default.db.table3", "/db/table3"),
                new ResourceMapping("default.db.table3", "/other/location2"))
        );

        List<ResourceDiffStreamRecord> fetchedEvents = getFetchedEvents();
        assertEquals(expectedRecords, fetchedEvents);

        assertEquals(Collections.singletonList(0L), eventsHolder.requestedOffsetIds);
        assertEquals(11L, eventFetcher.getLastHandledEventId());
    }

    @Test
    public void testHandleEventsForAllTableTypes() {
        TableType[] tableTypes = TableType.values();

        List<NotificationEvent> events = IntStream.range(0, tableTypes.length)
            .boxed()
            .flatMap(i -> allEventTypesForTableType(i * 100 + 1, tableTypes[i]))
            .collect(Collectors.toList());
        eventsHolder.addEvents(events);

        List<ResourceDiffStreamRecord> fetchedEvents = getFetchedEvents();
        assertEquals(SUPPORTED_TABLE_TYPES_COUNT * SUPPORTED_TABLE_OPERATIONS_COUNT, fetchedEvents.size());

        List<ResourceDiffStreamRecord> supportedRecords = fetchedEvents.stream()
            .filter(event -> event instanceof ResourceMappingDiff)
            .map(event -> (ResourceMappingDiff) event)
            .filter(event -> SUPPORTED_TABLE_TYPES.contains(getTableName(event)))
            .collect(Collectors.toList());
        assertEquals(SUPPORTED_TABLE_TYPES_COUNT * SUPPORTED_TABLE_OPERATIONS_COUNT, supportedRecords.size());

        assertEquals(Collections.singletonList(0L), eventsHolder.requestedOffsetIds);
        assertEquals(403L, eventFetcher.getLastHandledEventId());
    }

    private List<ResourceDiffStreamRecord> getFetchedEvents() {
        eventFetcher.pollRecordsBatch();
        return new ArrayList<>(eventFetcher.getOutputQueue());
    }

    private Stream<NotificationEvent> allEventTypesForTableType(long startId, TableType tableType) {
        String name = "cat.db." + tableType.toString();
        return Stream.of(
            newCreateTableEvent(startId, name, tableType, "some_location"),
            newAlterTableEvent(startId + 1,
                tableType,
                new ResourceMapping(name, "some_location"),
                new ResourceMapping(name + "_new", "some_location_new")),
            newDropTableEvent(startId + 2, name, tableType, "some_location")
        );
    }

    private ResourceMappingDiff diff(
        long id,
        HiveEntityType entityType,
        HiveEntityDiffType diffType,
        String name,
        String location
    ) {
        return diffBuilder(id, entityType, diffType)
            .oldEntity(new ResourceMapping(name, location))
            .build();
    }

    private ResourceMappingDiff updateDiff(
        long id,
        HiveEntityType entityType,
        ResourceMapping oldMapping,
        ResourceMapping newMapping
    ) {
        return diffBuilder(id, entityType, UPDATE)
            .oldEntity(oldMapping)
            .newEntity(newMapping)
            .build();
    }

    private ResourceMappingDiff.ResourceMappingDiffBuilder diffBuilder(
        long id, HiveEntityType entityType, HiveEntityDiffType diffType) {
        return ResourceMappingDiff.builder()
            .id(id)
            .sourceService(HIVE_SERVICE)
            .targetService(DEFAULT_LOCATION_SCHEME)
            .entityType(entityType.toString())
            .diffType(diffType.toString());

    }

    private NotificationEventResponse getEvents(MetastoreEventsHolder metastoreEventsHolder,
                                                InvocationOnMock invocation) {
        List<NotificationEvent> events = metastoreEventsHolder.getNextEvents(
            invocation.getArgument(0, Long.class),
            invocation.getArgument(1, Integer.class),
            invocation.getArgument(2, IMetaStoreClient.NotificationFilter.class)
        );
        return new NotificationEventResponse(events);
    }

    private String getTableName(ResourceMappingDiff diff) {
        String fullName = diff.getOldEntity().getName();
        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }

    private List<NotificationEvent> getDefaultTestEvents() {
        AtomicLong idSeq = new AtomicLong(1L);

        return Arrays.asList(
            newCreateDbEvent(idSeq.getAndIncrement(), "default.newdb", "/newdb"),
            newCreateTableEvent(idSeq.getAndIncrement(), "default.db.table1", TableType.EXTERNAL_TABLE, "/out/table1"),
            newCreateTableEvent(idSeq.getAndIncrement(), "default.db.table2", TableType.MANAGED_TABLE, "/db/table2"),
            newDropTableEvent(idSeq.getAndIncrement(), "default.db.view1", TableType.VIRTUAL_VIEW, "/doesnt/matter"),
            newAlterDbEvent(idSeq.getAndIncrement(),
                new ResourceMapping("default.newdb", "/newdb"),
                new ResourceMapping("default.newestdb", "/newestdb")),
            newDropTableEvent(idSeq.getAndIncrement(), "default.db.table2", TableType.MANAGED_TABLE, "/db/table2"),
            newAlterDbEvent(idSeq.getAndIncrement(),
                new ResourceMapping("default.newestdb", "/newestdb"),
                new ResourceMapping("default.newestdb", "/other/location")),
            newDropDbEvent(idSeq.getAndIncrement(), "default.newestdb", "/other/location"),
            newAlterTableEvent(idSeq.getAndIncrement(),
                TableType.EXTERNAL_TABLE,
                new ResourceMapping("default.db.table2", "/db/table2"),
                new ResourceMapping("default.db.table3", "/db/table3")),
            newDropTableEvent(idSeq.getAndIncrement(), "default.db.view", TableType.VIRTUAL_VIEW, "/out/view"),
            newAlterTableEvent(idSeq.getAndIncrement(),
                TableType.EXTERNAL_TABLE,
                new ResourceMapping("default.db.table3", "/db/table3"),
                new ResourceMapping("default.db.table3", "/other/location2"))
        );
    }

    private static class MetastoreEventsHolder {
        private final List<Long> requestedOffsetIds = new ArrayList<>();

        private final PriorityQueue<NotificationEvent> eventQueue = new PriorityQueue<>(
            Comparator.comparingLong(NotificationEvent::getEventId)
        );

        public void addEvents(Collection<NotificationEvent> events) {
            eventQueue.addAll(events);
        }

        public List<NotificationEvent> getNextEvents(long offsetId,
                                                     int batchSize,
                                                     IMetaStoreClient.NotificationFilter filter) {
            requestedOffsetIds.add(offsetId);
            return IntStream.range(0, eventQueue.size())
                .mapToObj(i -> eventQueue.poll())
                .filter(Objects::nonNull)
                .filter(event -> event.getEventId() > offsetId)
                .limit(batchSize)
                .filter(filter::accept)
                .collect(Collectors.toList());
        }
    }
}