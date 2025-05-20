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

import java.util.Optional;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrieHiveResourceMappingStoreTest {
    private TrieHiveResourceMappingStore mappingStore;

    @BeforeEach
    public void setUp() {
        mappingStore = new TrieHiveResourceMappingStore();
    }

    @Test
    public void testPutAndGet() {
        HiveEntity expectedEntity = new HiveEntity("db.table1", HiveObjectType.TABLE);
        mappingStore.put("/db/table1", expectedEntity);

        assertContains("/db/table1", expectedEntity);

        expectedEntity = new HiveEntity("db.db2", HiveObjectType.DATABASE);
        mappingStore.put("/db/table1", expectedEntity);

        assertContains("/db/table1", expectedEntity);
    }

    @Test
    public void testGetUnknownEntity() {
        assertNoValue("/db/table1");
        assertNoValue("/db/table2");

        mappingStore.put("/db/table2", new HiveEntity("db.table1", HiveObjectType.TABLE));

        assertNoValue("/db/table1");
    }

    @Test
    public void testGetPrefixEntity() {
        HiveEntity db = new HiveEntity("db", HiveObjectType.DATABASE);
        mappingStore.put("/db", db);

        assertContains("/db", db);
        assertContains("/db/", db);
        assertContains("/db/1", db);
        assertContains("/db/table", db);
        assertContains("/db/table/path", db);

        HiveEntity table = new HiveEntity("table", HiveObjectType.TABLE);
        mappingStore.put("/db/table", table);

        assertContains("/db/table", table);
        assertContains("/db/table/path", table);
        assertContains("/db/table2", db);
        assertContains("/db/table2/other_path", db);
        assertNoValue("/db2");
    }

    @Test
    public void testRemoveEntity() {
        HiveEntity db = new HiveEntity("db", HiveObjectType.DATABASE);
        mappingStore.put("/db", db);

        HiveEntity table = new HiveEntity("table", HiveObjectType.TABLE);
        mappingStore.put("/db/table", table);

        assertContains("/db/table/some/path", table);

        mappingStore.remove("/db/table");
        assertContains("/db/table/some/path", db);

        mappingStore.remove("/db");
        assertNoValue("/db/table/some/path");
    }

    @Test
    public void testRemovingBaseEntityRemovesChildren() {
        HiveEntity db = new HiveEntity("db", HiveObjectType.DATABASE);
        mappingStore.put("/db", db);

        HiveEntity db2 = new HiveEntity("db2", HiveObjectType.DATABASE);
        mappingStore.put("/db2", db2);

        HiveEntity table = new HiveEntity("table", HiveObjectType.TABLE);
        mappingStore.put("/db/table1", table);

        HiveEntity table2 = new HiveEntity("table2", HiveObjectType.TABLE);
        mappingStore.put("/db/table2", table2);

        HiveEntity table3 = new HiveEntity("table3", HiveObjectType.TABLE);
        mappingStore.put("/db2/table3", table3);

        HiveEntity subTable = new HiveEntity("subTable", HiveObjectType.TABLE);
        mappingStore.put("/db/table1/subTable", subTable);

        assertContains("/db", db);
        assertContains("/db/table1", table);
        assertContains("/db/table2", table2);
        assertContains("/db/table1/subTable", subTable);
        assertContains("/db/table1/subTable/somepath", subTable);
        assertContains("/db2", db2);
        assertContains("/db2/table3", table3);

        mappingStore.remove("/db");

        assertNoValue("/db");
        assertNoValue("/db/table1");
        assertNoValue("/db/table2");
        assertNoValue("/db/table1/subTable");
        assertNoValue("/db/table1/subTable/somepath");

        assertContains("/db2", db2);
        assertContains("/db2/table3", table3);
    }

    @Test
    public void testMoveEntity() {
        HiveEntity db = new HiveEntity("db", HiveObjectType.DATABASE);
        mappingStore.put("/db", db);

        HiveEntity db2 = new HiveEntity("db2", HiveObjectType.DATABASE);
        mappingStore.put("/db2", db2);

        HiveEntity table = new HiveEntity("table", HiveObjectType.TABLE);
        mappingStore.put("/db/table1", table);

        HiveEntity table2 = new HiveEntity("table2", HiveObjectType.TABLE);
        mappingStore.put("/db/table2", table2);

        HiveEntity table3 = new HiveEntity("table3", HiveObjectType.TABLE);
        mappingStore.put("/db2/table3", table3);

        HiveEntity subTable = new HiveEntity("subTable", HiveObjectType.TABLE);
        mappingStore.put("/db/table1/subTable", subTable);

        assertContains("/db", db);
        assertContains("/db/table1", table);
        assertContains("/db/table2", table2);
        assertContains("/db/table1/subTable", subTable);
        assertContains("/db/table1/subTable/somepath", subTable);
        assertContains("/db2", db2);
        assertContains("/db2/table3", table3);

        mappingStore.move("/db", "/db2", db2);

        assertNoValue("/db");
        assertNoValue("/db/table1");
        assertNoValue("/db/table2");
        assertNoValue("/db/table1/subTable");
        assertNoValue("/db/table1/subTable/somepath");

        assertContains("/db2", db2);
        assertContains("/db2/table1", table);
        assertContains("/db2/table2", table2);
        assertContains("/db2/table3", table3);
        assertContains("/db2/table1/subTable", subTable);
        assertContains("/db2/table1/subTable/somepath", subTable);
    }

    private void assertContains(String path, HiveEntity expectedEntity) {
        Optional<HiveEntity> hiveEntity = mappingStore.get(path);

        assertTrue(hiveEntity.isPresent());
        assertEquals(expectedEntity, hiveEntity.get());
    }

    private void assertNoValue(String path) {
        assertFalse(mappingStore.get(path).isPresent());
    }
}