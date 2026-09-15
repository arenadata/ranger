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

class StarRocksHiveMappingFetcherTest {
    private TrieHiveResourceMappingStore mappingStore;
    private StarRocksHiveMappingFetcher mappingFetcher;

    @BeforeEach
    void setUp() {
        mappingStore = new TrieHiveResourceMappingStore();
        mappingFetcher = new StarRocksHiveMappingFetcher(null, mappingStore, -1L, -1L, "starrocks");
    }

    @Test
    void testCreateTableMappingIsStoredByStarRocksPath() {
        mappingFetcher.applyDiff(diff("CREATE", HiveObjectType.TABLE,
            mapping("hive.db1.t1", "Hive_Catalog.db1.t1"), null));

        Optional<HiveEntity> entity = mappingStore.get("/hive_catalog/db1/t1");
        assertTrue(entity.isPresent());
        assertEquals(Arrays.asList("hive", "db1", "t1"), entity.get().getNameSegments());
        assertEquals(HiveObjectType.TABLE, entity.get().getType());

        // a column of the table resolves to the table mapping
        assertEquals(entity, mappingStore.get("/hive_catalog/db1/t1/c1"));
        assertFalse(mappingStore.get("/iceberg_catalog/db1/t1").isPresent());
    }

    @Test
    void testDatabaseMappingCoversItsTables() {
        mappingFetcher.applyDiff(diff("CREATE", HiveObjectType.DATABASE,
            mapping("hive.db1", "hive_catalog.db1"), null));

        Optional<HiveEntity> entity = mappingStore.get("/hive_catalog/db1/unknown_table");
        assertTrue(entity.isPresent());
        assertEquals(HiveObjectType.DATABASE, entity.get().getType());
    }

    @Test
    void testRenameMovesMapping() {
        mappingFetcher.applyDiff(diff("CREATE", HiveObjectType.TABLE,
            mapping("hive.db1.t1", "hive_catalog.db1.t1"), null));
        mappingFetcher.applyDiff(diff("UPDATE", HiveObjectType.TABLE,
            mapping("hive.db1.t1", "hive_catalog.db1.t1"),
            mapping("hive.db1.t2", "hive_catalog.db1.t2")));

        assertFalse(mappingStore.get("/hive_catalog/db1/t1").isPresent());
        Optional<HiveEntity> entity = mappingStore.get("/hive_catalog/db1/t2");
        assertTrue(entity.isPresent());
        assertEquals("hive.db1.t2", entity.get().fullName());
    }

    @Test
    void testDeleteRemovesMapping() {
        mappingFetcher.applyDiff(diff("CREATE", HiveObjectType.TABLE,
            mapping("hive.db1.t1", "hive_catalog.db1.t1"), null));
        mappingFetcher.applyDiff(diff("DELETE", HiveObjectType.TABLE,
            mapping("hive.db1.t1", "hive_catalog.db1.t1"), null));

        assertFalse(mappingStore.get("/hive_catalog/db1/t1").isPresent());
    }

    private static ResourceMappingDiff diff(String diffType,
                                            HiveObjectType entityType,
                                            ResourceMapping oldEntity,
                                            ResourceMapping newEntity) {
        return new ResourceMappingDiff(oldEntity, newEntity, entityType.name(), diffType, 1L, "hive", "starrocks");
    }

    private static ResourceMapping mapping(String name, String location) {
        return new ResourceMapping(name, location);
    }
}
