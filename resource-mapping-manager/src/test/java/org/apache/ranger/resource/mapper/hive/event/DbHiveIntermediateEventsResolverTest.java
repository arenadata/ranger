package org.apache.ranger.resource.mapper.hive.event;

import static org.apache.ranger.resource.mapper.hive.event.ResourceMappingDiffFactory.diff;
import static org.apache.ranger.resource.mapper.hive.event.ResourceMappingDiffFactory.updateDiff;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.CREATE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.DELETE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityType.TABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

class DbHiveIntermediateEventsResolverTest {
    private MockDiffDao diffDao;

    private DbHiveIntermediateEventsResolver eventsResolver;

    @BeforeEach
    public void setUp() {
        diffDao = new MockDiffDao();
        eventsResolver = new DbHiveIntermediateEventsResolver(diffDao);
    }

    @Test
    public void testResolveEvents() {
        List<ResourceDiffStreamRecord> records = Arrays.asList(
            updateDiff(1L, TABLE,
                new ResourceMapping("old", "/old"),
                new ResourceMapping("new", "/new")),
            diff(2L, TABLE, CREATE, "a", "/a"),
            diff(3L, TABLE, CREATE, "b", "/b"),
            diff(4L, TABLE, DELETE, "a", "/a"),
            updateDiff(5L, TABLE,
                new ResourceMapping("b", "/b"),
                new ResourceMapping("a", "/a-new"))
        );

        Set<ResourceMapping> expectedDeletedMappings = Sets.newHashSet(
            new ResourceMapping("old", "/old"),
            new ResourceMapping("a", "/a"),
            new ResourceMapping("b", "/b")
        );
        List<ResourceMappingDiff> expectedInsertedDiffs = Arrays.asList(
            diff(1L, TABLE, CREATE, "new", "/new"),
            diff(5L, TABLE, CREATE, "a", "/a-new")
        );

        checkRecords(records, expectedDeletedMappings, expectedInsertedDiffs);
    }

    private void checkRecords(List<ResourceDiffStreamRecord> records,
                              Set<ResourceMapping> expectedDeletedMappings,
                              List<ResourceMappingDiff> expectedInsertedDiffs) {
        records.forEach(eventsResolver::handle);
        eventsResolver.flush();

        assertEquals(expectedDeletedMappings, diffDao.deletedMappings);
        assertEquals(expectedInsertedDiffs, diffDao.insertedDiffs);
    }

    private static class MockDiffDao implements ResourceMappingDiffDao {
        private final Set<ResourceMapping> deletedMappings = new HashSet<>();
        private final List<ResourceMappingDiff> insertedDiffs = new ArrayList<>();

        @Override
        public void deleteDiffsFor(ResourceMapping mapping) {
            deletedMappings.add(mapping);
        }

        @Override
        public void insertDiff(ResourceMappingDiff entityDiff) {
            insertedDiffs.add(entityDiff);
        }

        @Override
        public void deleteAllDiffs() {
            // not used
        }

        @Override
        public Optional<Long> getLatestExternalDiffId(String sourceServiceName) {
            // not used
            return Optional.empty();
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }
}