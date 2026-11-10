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

/**
 * The mapping found in the store covers a prefix of the StarRocks resource; the levels below it come
 * from the request. Every combination of the two is checked here.
 */
class StarRocksHiveResourcesTest {
    private static final HiveEntity DATABASE = new HiveEntity("hive.db1", HiveObjectType.DATABASE);
    private static final HiveEntity TABLE = new HiveEntity("hive.db1.t1", HiveObjectType.TABLE);

    // ---- a database mapping ----

    @Test
    void databaseMappingOfADatabaseRequest() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog", "db1"));

        assertEquals(HiveObjectType.DATABASE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertNull(hiveResource.getTable());
        assertNull(hiveResource.getColumn());
    }

    /** a database mapping authorizes every table of that database, including ones RMM never published */
    @Test
    void databaseMappingCarriesTheTableOver() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog", "db1", "unpublished_view"));

        assertEquals(HiveObjectType.TABLE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("unpublished_view", hiveResource.getTable());
        assertNull(hiveResource.getColumn());
    }

    @Test
    void databaseMappingCarriesTableAndColumnOver() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog", "db1", "t1", "c1"));

        assertEquals(HiveObjectType.COLUMN, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("t1", hiveResource.getTable());
        assertEquals("c1", hiveResource.getColumn());
    }

    // ---- a table mapping ----

    @Test
    void tableMappingOfATableRequest() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            TABLE, resource("hive_catalog", "db1", "t1"));

        assertEquals(HiveObjectType.TABLE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("t1", hiveResource.getTable());
    }

    @Test
    void tableMappingCarriesTheColumnOver() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            TABLE, resource("hive_catalog", "db1", "t1", "c1"));

        assertEquals(HiveObjectType.COLUMN, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("t1", hiveResource.getTable());
        assertEquals("c1", hiveResource.getColumn());
    }

    /** the Hive names come from the mapping, so a table renamed in StarRocks is still authorized by its Hive name */
    @Test
    void theHiveNamesWinOverTheRequestedOnes() {
        HiveEntity renamed = new HiveEntity("hive.hive_db.hive_table", HiveObjectType.TABLE);

        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            renamed, resource("hive_catalog", "db1", "t1", "c1"));

        assertEquals("hive_db", hiveResource.getDatabase());
        assertEquals("hive_table", hiveResource.getTable());
        assertEquals("c1", hiveResource.getColumn());
    }

    /** a table mapping cannot be found for a shallower request, but it must not produce a broken resource either */
    @Test
    void tableMappingOfADatabaseRequestStaysATable() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            TABLE, resource("hive_catalog", "db1"));

        assertEquals(HiveObjectType.TABLE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
        assertEquals("t1", hiveResource.getTable());
    }

    /** a resource the path builder rejects carries no levels to append */
    @Test
    void anUnmappableResourceLeavesTheMappedPrefixAlone() {
        RangerHiveResource hiveResource = StarRocksHiveResources.toHiveResource(
            DATABASE, resource("hive_catalog"));

        assertEquals(HiveObjectType.DATABASE, hiveResource.getObjectType());
        assertEquals("db1", hiveResource.getDatabase());
    }

    // ---- malformed mappings ----

    @Test
    void aDatabaseEntityWithoutACatalogSegmentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StarRocksHiveResources.toHiveResource(
            new HiveEntity("db1", HiveObjectType.DATABASE), resource("hive_catalog", "db1")));
    }

    @Test
    void aTableEntityWithoutATableSegmentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StarRocksHiveResources.toHiveResource(
            new HiveEntity("hive.db1", HiveObjectType.TABLE), resource("hive_catalog", "db1", "t1")));
    }
}
