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

import static org.apache.ranger.hive.chained.starrocks.StarRocksResourcePathTest.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.junit.jupiter.api.Test;

class StarRocksHiveResourcesTest {
    private static final HiveEntity DATABASE = new HiveEntity("hive.db1", HiveObjectType.DATABASE);
    private static final HiveEntity TABLE = new HiveEntity("hive.db1.t1", HiveObjectType.TABLE);

    @Test
    void testDatabaseMappingForDatabaseRequest() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog", "db1"));

        assertEquals(HiveObjectType.DATABASE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertNull(hiveResource.getTable());
    }

    @Test
    void testDatabaseMappingCarriesTableOver() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog", "db1", "T1"));

        assertEquals(HiveObjectType.TABLE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("T1", hiveResource.getTable());
        assertNull(hiveResource.getColumn());
    }

    @Test
    void testDatabaseMappingCarriesTableAndColumnOver() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog", "db1", "t1", "c1"));

        assertEquals(HiveObjectType.COLUMN, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("t1", hiveResource.getTable());
        assertEquals("c1", hiveResource.getColumn());
    }

    @Test
    void testTableMappingForTableRequest() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            TABLE, resource("hive_catalog", "db1", "t1"));

        assertEquals(HiveObjectType.TABLE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("t1", hiveResource.getTable());
    }

    @Test
    void testTableMappingUsesHiveNamesForRenamedTable() {
        HiveEntity renamed = new HiveEntity("hive.db1.t1_hive", HiveObjectType.TABLE);

        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            renamed, resource("hive_catalog", "db1", "t1"));

        assertEquals("t1_hive", hiveResource.getTable());
    }

    @Test
    void testTableMappingCarriesColumnOver() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            TABLE, resource("hive_catalog", "db1", "t1", "c1"));

        assertEquals(HiveObjectType.COLUMN, hiveResource.getObjectType());
        assertEquals("c1", hiveResource.getColumn());
    }

    @Test
    void testMalformedEntities() {
        assertThrows(IllegalArgumentException.class, () -> StarRocksHiveResources.toHiveResource(
            new HiveEntity("db1", HiveObjectType.DATABASE), resource("hive_catalog", "db1")));
        assertThrows(IllegalArgumentException.class, () -> StarRocksHiveResources.toHiveResource(
            new HiveEntity("hive.db1", HiveObjectType.TABLE), resource("hive_catalog", "db1", "t1")));
    }
}
