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

package org.apache.ranger.hive.chained.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.plugin.model.ResourceMapping;
import org.apache.ranger.plugin.model.ResourceMappingDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HiveMappingFetcherTest {

    private HiveMappingFetcher mappingFetcher;
    private MockHiveResourceMappingStore mappingStore;

    @BeforeEach
    public void setUp() {
        mappingStore = new MockHiveResourceMappingStore();
        mappingFetcher = new HiveMappingFetcher(
            null,
            mappingStore,
            -1L,
            -1L,
            "test"
        );
    }

    private static Stream<HiveObjectType> objectTypesParamSource() {
        return Stream.of(
            HiveObjectType.TABLE,
            HiveObjectType.DATABASE
        );
    }

    @ParameterizedTest
    @MethodSource(value = "objectTypesParamSource")
    public void testApplyCreateTable(HiveObjectType objectType) {
        ResourceMappingDiff createDiff = newCreateDiff(objectType, "composite.name", "/location");
        mappingFetcher.applyDiff(createDiff);

        Optional<HiveEntity> maybeEntity = mappingStore.get("/location");
        assertTrue(maybeEntity.isPresent());
        assertEquals(Arrays.asList("composite", "name"), maybeEntity.get().getNameSegments());
        assertEquals(objectType, maybeEntity.get().getType());
    }

    @ParameterizedTest
    @MethodSource(value = "objectTypesParamSource")
    public void testApplyDeleteTable(HiveObjectType objectType) {
        ResourceMappingDiff createDiff = newCreateDiff(objectType, "complex.name", "/location");
        mappingFetcher.applyDiff(createDiff);
        assertTrue(mappingStore.get("/location").isPresent());

        ResourceMappingDiff deleteDiff = newDeleteDiff(objectType, "composite.name2", "/location_2");
        mappingFetcher.applyDiff(deleteDiff);
        assertTrue(mappingStore.get("/location").isPresent());

        deleteDiff = newDeleteDiff(objectType, "complex.name", "/location");
        mappingFetcher.applyDiff(deleteDiff);
        assertFalse(mappingStore.get("/location").isPresent());
    }

    @ParameterizedTest
    @MethodSource(value = "objectTypesParamSource")
    public void testApplyUpdateTableName(HiveObjectType objectType) {
        ResourceMappingDiff createDiff = newCreateDiff(objectType, "complex.name", "/location1");
        mappingFetcher.applyDiff(createDiff);
        assertTrue(mappingStore.get("/location1").isPresent());
        assertEquals("complex.name", mappingStore.get("/location1").get().fullName());

        ResourceMappingDiff updateDiff = newTestDiff(
            HiveMappingFetcher.HiveEntityDiffType.UPDATE,
            objectType,
            new ResourceMapping("complex.name", "/location1"),
            new ResourceMapping("another.name.2", "/location1")
        );
        mappingFetcher.applyDiff(updateDiff);
        assertTrue(mappingStore.get("/location1").isPresent());
        assertEquals("another.name.2", mappingStore.get("/location1").get().fullName());
    }

    @ParameterizedTest
    @MethodSource(value = "objectTypesParamSource")
    public void testApplyUpdateTableLocation(HiveObjectType objectType) {
        ResourceMappingDiff createDiff = newCreateDiff(objectType, "complex.name", "/location1");
        mappingFetcher.applyDiff(createDiff);
        assertTrue(mappingStore.get("/location1").isPresent());
        assertEquals("complex.name", mappingStore.get("/location1").get().fullName());

        ResourceMappingDiff updateOnlyLocationDiff = newTestDiff(
            HiveMappingFetcher.HiveEntityDiffType.UPDATE,
            objectType,
            new ResourceMapping("complex.name", "/location1"),
            new ResourceMapping("complex.name", "/location2")
        );
        mappingFetcher.applyDiff(updateOnlyLocationDiff);
        assertFalse(mappingStore.get("/location1").isPresent());
        assertTrue(mappingStore.get("/location2").isPresent());
        assertEquals("complex.name", mappingStore.get("/location2").get().fullName());

        ResourceMappingDiff updateLocationAndNameDiff = newTestDiff(
            HiveMappingFetcher.HiveEntityDiffType.UPDATE,
            objectType,
            new ResourceMapping("complex.name", "/location2"),
            new ResourceMapping("another.name", "/location3")
        );
        mappingFetcher.applyDiff(updateLocationAndNameDiff);
        assertFalse(mappingStore.get("/location2").isPresent());
        assertTrue(mappingStore.get("/location3").isPresent());
        assertEquals("another.name", mappingStore.get("/location3").get().fullName());
    }

    private ResourceMappingDiff newCreateDiff(HiveObjectType objectType, String name, String location) {
        return newTestDiff(HiveMappingFetcher.HiveEntityDiffType.CREATE,
            objectType, new ResourceMapping(name, location), null);
    }

    private ResourceMappingDiff newDeleteDiff(HiveObjectType objectType, String name, String location) {
        return newTestDiff(HiveMappingFetcher.HiveEntityDiffType.DELETE,
            objectType, new ResourceMapping(name, location), null);
    }

    private ResourceMappingDiff newTestDiff(
        HiveMappingFetcher.HiveEntityDiffType diffType,
        HiveObjectType objectType,
        ResourceMapping oldEntity,
        ResourceMapping newEntity) {
        return new ResourceMappingDiff(
            oldEntity,
            newEntity,
            objectType.toString(),
            diffType.toString(),
            1L,
            "hive",
            "otherService"
        );
    }

    private static class MockHiveResourceMappingStore implements HiveResourceMappingStore {
        private final Map<String, HiveEntity> mappings = new HashMap<>();

        @Override
        public Optional<HiveEntity> get(String path) {
            return Optional.ofNullable(mappings.get(path));
        }

        @Override
        public void put(String path, HiveEntity value) {
            mappings.put(path, value);
        }

        @Override
        public void remove(String path) {
            mappings.remove(path);
        }

        @Override
        public void move(String oldPath, String newPath, HiveEntity value) {
            remove(oldPath);
            put(newPath, value);
        }

        @Override
        public void commit(long commitId) {
            // no-op
        }

        @Override
        public Optional<Long> getLastCommitId() {
            // no-op
            return Optional.empty();
        }
    }
}