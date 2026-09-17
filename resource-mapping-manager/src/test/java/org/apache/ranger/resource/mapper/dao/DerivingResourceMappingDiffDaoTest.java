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

package org.apache.ranger.resource.mapper.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.apache.ranger.resource.mapper.starrocks.StarRocksResourceMappingDeriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;

/**
 * A metastore event and everything derived from it have to reach the diff table together: the
 * fetcher moves its cursor past the event once, and what was not written then is never written.
 */
class DerivingResourceMappingDiffDaoTest {
    private static final String HDFS_LOCATION = "hdfs://nn:8020/warehouse/db1.db/t1";

    private RecordingDao delegate;
    private DerivingResourceMappingDiffDao dao;

    @BeforeEach
    void setUp() {
        delegate = new RecordingDao();
        dao = new DerivingResourceMappingDiffDao(delegate, Collections.singletonList(
            new StarRocksResourceMappingDeriver(Arrays.asList("hive_catalog", "iceberg_catalog"))));
    }

    // ---- inserts ----

    @Test
    void theOriginalAndTheDerivedDiffsAreWrittenInOneTransaction() {
        dao.insertDiff(createDiff());

        assertEquals(Arrays.asList(
            "begin",
            "insert hdfs://nn:8020/warehouse/db1.db/t1",
            "insert hive_catalog.db1.t1",
            "insert iceberg_catalog.db1.t1",
            "commit"), delegate.calls);
    }

    @Test
    void aFailedDerivedInsertRollsTheOriginalBack() {
        delegate.failOn = "iceberg_catalog.db1.t1";

        assertThrows(IllegalStateException.class, () -> dao.insertDiff(createDiff()));

        assertEquals("rollback", delegate.calls.get(delegate.calls.size() - 1),
            "the original insert must not be committed on its own");
    }

    @Test
    void aFailedOriginalInsertDerivesNothing() {
        delegate.failOn = HDFS_LOCATION;

        assertThrows(IllegalStateException.class, () -> dao.insertDiff(createDiff()));

        assertEquals(Arrays.asList("begin", "insert " + HDFS_LOCATION, "rollback"), delegate.calls);
    }

    @Test
    void withoutDeriversTheDiffIsJustPassedOn() {
        DerivingResourceMappingDiffDao plain =
            new DerivingResourceMappingDiffDao(delegate, Collections.emptyList());

        plain.insertDiff(createDiff());

        assertEquals(Arrays.asList("begin", "insert " + HDFS_LOCATION, "commit"), delegate.calls);
    }

    // ---- deletes ----

    @Test
    void aDeleteRemovesTheDerivedMappingsInTheSameTransaction() {
        dao.deleteDiffsFor(mapping("hive.db1.t1", HDFS_LOCATION));

        assertEquals(Arrays.asList(
            "begin",
            "delete hdfs://nn:8020/warehouse/db1.db/t1",
            "delete hive_catalog.db1.t1",
            "delete iceberg_catalog.db1.t1",
            "commit"), delegate.calls);
    }

    @Test
    void aFailedDerivedDeleteRollsTheOriginalBack() {
        delegate.failOn = "hive_catalog.db1.t1";

        assertThrows(IllegalStateException.class,
            () -> dao.deleteDiffsFor(mapping("hive.db1.t1", HDFS_LOCATION)));

        assertEquals("rollback", delegate.calls.get(delegate.calls.size() - 1));
    }

    // ---- everything else is the delegate's business ----

    @Test
    void readsAndFullWipesArePassedThrough() throws Exception {
        delegate.latestExternalDiffId = 5L;

        assertEquals(Optional.of(5L), dao.getLatestExternalDiffId("hive"));

        dao.deleteAllDiffs();

        assertTrue(delegate.calls.contains("deleteAll"));
        assertEquals("hive", delegate.latestDiffIdSourceService);
    }

    /**
     * The decorator always asks for a transaction; joining the one the snapshot collector has open
     * on the same data source is what {@code PROPAGATION_REQUIRED} does at run time.
     */
    @Test
    void theDecoratorAlwaysOpensATransaction() {
        dao.execute(status -> {
            dao.insertDiff(createDiff());
            return null;
        });

        assertEquals(2, Collections.frequency(delegate.calls, "begin"));
        assertEquals(2, Collections.frequency(delegate.calls, "commit"));
        assertEquals("commit", delegate.calls.get(delegate.calls.size() - 1));
    }

    // ---- helpers ----

    private static ResourceMappingDiff createDiff() {
        return MetastoreEntityDiffFactory.createEntity(
            "hive.db1.t1", HiveEntityType.TABLE, HDFS_LOCATION, 1L);
    }

    private static ResourceMapping mapping(String name, String location) {
        return ResourceMapping.builder().name(name).location(location).build();
    }

    /** records the calls it receives and refuses to write outside a transaction */
    private static class RecordingDao implements ResourceMappingDiffDao {
        final List<String> calls = new ArrayList<>();
        String failOn;
        Long latestExternalDiffId;
        String latestDiffIdSourceService;
        private int transactionDepth;

        @Override
        public void deleteDiffsFor(ResourceMapping mapping) {
            record("delete " + mapping.getLocation());
        }

        @Override
        public void deleteAllDiffs() {
            calls.add("deleteAll");
        }

        @Override
        public void insertDiff(ResourceMappingDiff entityDiff) {
            record("insert " + entityDiff.getOldEntity().getLocation());
        }

        @Override
        public Optional<Long> getLatestExternalDiffId(String sourceServiceName) {
            latestDiffIdSourceService = sourceServiceName;
            return Optional.ofNullable(latestExternalDiffId);
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            transactionDepth++;
            calls.add("begin");
            try {
                T result = action.doInTransaction(null);
                calls.add("commit");
                return result;
            } catch (RuntimeException exception) {
                calls.add("rollback");
                throw exception;
            } finally {
                transactionDepth--;
            }
        }

        private void record(String call) {
            assertTrue(transactionDepth > 0, call + " happened outside a transaction");
            calls.add(call);
            if (failOn != null && call.endsWith(failOn)) {
                throw new IllegalStateException("write failed: " + call);
            }
        }
    }
}
