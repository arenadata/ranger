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

package org.apache.ranger.resource.mapper.starrocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.junit.jupiter.api.Test;

class StarRocksResourceMappingDeriverTest {
    private static final String HDFS_LOCATION = "hdfs://nn:8020/warehouse/db1.db/t1";

    private final StarRocksResourceMappingDeriver deriver =
        new StarRocksResourceMappingDeriver(Arrays.asList("hive_catalog", "iceberg_catalog"));

    @Test
    void testDeriveCreateTableDiffPerCatalog() {
        ResourceMappingDiff createDiff = MetastoreEntityDiffFactory.createEntity(
            "hive.db1.t1", HiveEntityType.TABLE, HDFS_LOCATION, 42L);

        List<ResourceMappingDiff> derived = deriver.derive(createDiff);

        assertEquals(2, derived.size());
        ResourceMappingDiff first = derived.get(0);
        assertEquals(42L, first.getId());
        assertEquals(HiveEntityType.TABLE.name(), first.getEntityType());
        assertEquals(HiveEntityDiffType.CREATE.name(), first.getDiffType());
        assertEquals("hive", first.getSourceService());
        assertEquals("starrocks", first.getTargetService());
        assertEquals("hive.db1.t1", first.getOldEntity().getName());
        assertEquals("hive_catalog.db1.t1", first.getOldEntity().getLocation());
        assertNull(first.getNewEntity());
        assertEquals("iceberg_catalog.db1.t1", derived.get(1).getOldEntity().getLocation());
    }

    @Test
    void testDeriveDatabaseDiff() {
        ResourceMappingDiff dropDiff = MetastoreEntityDiffFactory.dropEntity(
            "hive.db1", "hdfs://nn:8020/warehouse/db1.db", HiveEntityType.DATABASE, 7L);

        List<ResourceMappingDiff> derived = deriver.derive(dropDiff);

        assertEquals(2, derived.size());
        assertEquals(HiveEntityDiffType.DELETE.name(), derived.get(0).getDiffType());
        assertEquals("hive_catalog.db1", derived.get(0).getOldEntity().getLocation());
    }

    @Test
    void testDeriveRenameDiffKeepsBothEntities() {
        ResourceMappingDiff updateDiff = MetastoreEntityDiffFactory.updateEntity(
            ResourceMapping.builder().name("hive.db1.t1").location(HDFS_LOCATION).build(),
            ResourceMapping.builder().name("hive.db1.t2").location("hdfs://nn:8020/warehouse/db1.db/t2").build(),
            HiveEntityType.TABLE, 8L);

        List<ResourceMappingDiff> derived = deriver.derive(updateDiff);

        assertEquals(2, derived.size());
        assertEquals("hive_catalog.db1.t1", derived.get(0).getOldEntity().getLocation());
        assertEquals("hive.db1.t2", derived.get(0).getNewEntity().getName());
        assertEquals("hive_catalog.db1.t2", derived.get(0).getNewEntity().getLocation());
    }

    @Test
    void testDoesNotDeriveFromStarRocksOrForeignDiffs() {
        ResourceMappingDiff starRocksDiff = ResourceMappingDiff.builder()
            .id(1L)
            .entityType(HiveEntityType.TABLE.name())
            .diffType(HiveEntityDiffType.CREATE.name())
            .sourceService("hive")
            .targetService("starrocks")
            .oldEntity(ResourceMapping.builder().name("hive.db1.t1").location("hive_catalog.db1.t1").build())
            .build();
        assertTrue(deriver.derive(starRocksDiff).isEmpty());

        ResourceMappingDiff foreignDiff = ResourceMappingDiff.builder()
            .id(1L)
            .entityType(HiveEntityType.TABLE.name())
            .diffType(HiveEntityDiffType.CREATE.name())
            .sourceService("other")
            .targetService("hdfs")
            .oldEntity(ResourceMapping.builder().name("hive.db1.t1").location(HDFS_LOCATION).build())
            .build();
        assertTrue(deriver.derive(foreignDiff).isEmpty());
    }

    @Test
    void testSkipsMalformedNames() {
        ResourceMappingDiff createDiff = MetastoreEntityDiffFactory.createEntity(
            "db1", HiveEntityType.DATABASE, "hdfs://nn:8020/warehouse/db1.db", 1L);
        assertTrue(deriver.derive(createDiff).isEmpty());
        assertTrue(deriver.derive(ResourceMapping.builder().name("hive..t1").location(HDFS_LOCATION).build())
            .isEmpty());
    }

    @Test
    void testDeriveMappings() {
        List<ResourceMapping> derived = deriver.derive(
            ResourceMapping.builder().name("hive.db1.t1").location(HDFS_LOCATION).build());

        assertEquals(2, derived.size());
        assertEquals("hive.db1.t1", derived.get(0).getName());
        assertEquals("hive_catalog.db1.t1", derived.get(0).getLocation());
        assertEquals("iceberg_catalog.db1.t1", derived.get(1).getLocation());
    }

    @Test
    void testNoCatalogsNoMappings() {
        StarRocksResourceMappingDeriver emptyDeriver =
            new StarRocksResourceMappingDeriver(Collections.emptyList());
        ResourceMappingDiff createDiff = MetastoreEntityDiffFactory.createEntity(
            "hive.db1.t1", HiveEntityType.TABLE, HDFS_LOCATION, 42L);

        assertTrue(emptyDeriver.derive(createDiff).isEmpty());
    }

    @Test
    void testToStarRocksName() {
        assertEquals(Optional.of("cat.db1.t1"),
            StarRocksResourceMappingDeriver.toStarRocksName("cat", "hive.db1.t1"));
        assertEquals(Optional.of("cat.db1"),
            StarRocksResourceMappingDeriver.toStarRocksName("cat", "hive.db1"));
        assertFalse(StarRocksResourceMappingDeriver.toStarRocksName("cat", "hive").isPresent());
        assertFalse(StarRocksResourceMappingDeriver.toStarRocksName("cat", null).isPresent());
    }
}
