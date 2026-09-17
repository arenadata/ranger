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

package org.apache.ranger.hive.chained.starrocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Optional;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.hive.chained.mapping.TrieHiveResourceMappingStore;
import org.apache.ranger.plugin.model.ResourceMapping;
import org.apache.ranger.plugin.model.ResourceMappingDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What the mapping store holds after the diffs RMM publishes for the StarRocks target are applied. */
class StarRocksHiveMappingFetcherTest {
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private TrieHiveResourceMappingStore mappingStore;
    private StarRocksHiveMappingFetcher mappingFetcher;

    @BeforeEach
    void setUp() {
        mappingStore = new TrieHiveResourceMappingStore();
        mappingFetcher = new StarRocksHiveMappingFetcher(null, mappingStore, -1L, -1L, "starrocks");
    }

    // ---- create ----

    @Test
    void aTableIsStoredUnderItsStarRocksPath() {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);

        HiveEntity entity = required("/hive_catalog/db1/t1");
        assertEquals(Arrays.asList("hive", "db1", "t1"), entity.getNameSegments());
        assertEquals(HiveObjectType.TABLE, entity.getType());
    }

    @Test
    void aTableMappingAlsoCoversItsColumns() {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);

        assertEquals(mappingStore.get("/hive_catalog/db1/t1"), mappingStore.get("/hive_catalog/db1/t1/c1"));
    }

    @Test
    void aDatabaseMappingCoversTablesItDoesNotName() {
        apply(CREATE, HiveObjectType.DATABASE, mapping("hive.db1", "hive_catalog.db1"), null);

        HiveEntity entity = required("/hive_catalog/db1/some_view/some_column");
        assertEquals(HiveObjectType.DATABASE, entity.getType());
    }

    @ParameterizedTest(name = "location [{0}]")
    @ValueSource(strings = {"hive_catalog.db1.t1", "/hive_catalog/db1/t1", "hive_catalog/db1.t1",
        "hive_catalog.DB1.T1", " hive_catalog.db1.t1 "})
    void everyLocationFormReachesTheSamePath(String location) {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", location), null);

        assertTrue(mappingStore.get("/hive_catalog/db1/t1").isPresent());
    }


    /** two catalogs that differ only in case are different catalogs and must not share a mapping */
    @Test
    void catalogsThatDifferInCaseDoNotShareMappings() {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "Sales.db1.t1"), null);

        assertTrue(mappingStore.get("/Sales/db1/t1").isPresent());
        assertFalse(mappingStore.get("/sales/db1/t1").isPresent(),
            "a lower-cased catalog must not pick up the mapping of the capitalised one");
    }

    @Test
    void unmappedResourcesAreNotFound() {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);

        assertFalse(mappingStore.get("/other_catalog/db1/t1").isPresent());
        assertFalse(mappingStore.get("/hive_catalog/db2/t1").isPresent());
        assertFalse(mappingStore.get("/default_catalog/db1/t1").isPresent());
    }

    // ---- update ----

    @Test
    void aRenameMovesTheMapping() {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);
        apply(UPDATE, HiveObjectType.TABLE,
            mapping("hive.db1.t1", "hive_catalog.db1.t1"),
            mapping("hive.db1.t2", "hive_catalog.db1.t2"));

        assertFalse(mappingStore.get("/hive_catalog/db1/t1").isPresent());
        assertEquals("hive.db1.t2", required("/hive_catalog/db1/t2").fullName());
    }

    // ---- delete ----

    @Test
    void aDropRemovesTheMapping() {
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);
        apply(DELETE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);

        assertFalse(mappingStore.get("/hive_catalog/db1/t1").isPresent());
    }

    @Test
    void droppingATableFallsBackToItsDatabaseMapping() {
        apply(CREATE, HiveObjectType.DATABASE, mapping("hive.db1", "hive_catalog.db1"), null);
        apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);
        apply(DELETE, HiveObjectType.TABLE, mapping("hive.db1.t1", "hive_catalog.db1.t1"), null);

        assertEquals(HiveObjectType.DATABASE, required("/hive_catalog/db1/t1").getType());
    }

    // ---- malformed input ----

    @ParameterizedTest(name = "location [{0}]")
    @ValueSource(strings = {"", "   ", "hive_catalog..t1", "."})
    void aMalformedLocationIsRejected(String location) {
        assertThrows(IllegalArgumentException.class,
            () -> apply(CREATE, HiveObjectType.TABLE, mapping("hive.db1.t1", location), null));
    }

    // ---- helpers ----

    private HiveEntity required(String path) {
        Optional<HiveEntity> entity = mappingStore.get(path);
        assertTrue(entity.isPresent(), "no mapping for " + path);
        return entity.get();
    }

    private void apply(String diffType,
                       HiveObjectType entityType,
                       ResourceMapping oldEntity,
                       ResourceMapping newEntity) {
        mappingFetcher.applyDiff(new ResourceMappingDiff(
            oldEntity, newEntity, entityType.name(), diffType, 1L, "hive", "starrocks"));
    }

    private static ResourceMapping mapping(String name, String location) {
        return new ResourceMapping(name, location);
    }
}
