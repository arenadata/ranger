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
import java.util.stream.Collectors;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Hive entity is published once more per configured StarRocks external catalog.
 */
class StarRocksResourceMappingDeriverTest {
    private static final String HDFS_LOCATION = "hdfs://nn:8020/warehouse/db1.db/t1";

    private final StarRocksResourceMappingDeriver deriver =
        new StarRocksResourceMappingDeriver(Arrays.asList("hive_catalog", "iceberg_catalog"));

    // ---- one derived diff per catalog ----

    @Test
    void aTableIsDerivedForEveryCatalog() {
        ResourceMappingDiff diff = MetastoreEntityDiffFactory.createEntity(
            "hive.db1.t1", HiveEntityType.TABLE, HDFS_LOCATION, 42L);

        List<ResourceMappingDiff> derived = deriver.derive(diff);

        assertEquals(2, derived.size());
        ResourceMappingDiff first = derived.get(0);
        assertEquals(42L, first.getId(), "the derived diff belongs to the same metastore event");
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
    void aDatabaseIsDerivedForEveryCatalog() {
        ResourceMappingDiff diff = MetastoreEntityDiffFactory.dropEntity(
            "hive.db1", "hdfs://nn:8020/warehouse/db1.db", HiveEntityType.DATABASE, 7L);

        List<ResourceMappingDiff> derived = deriver.derive(diff);

        assertEquals(Arrays.asList("hive_catalog.db1", "iceberg_catalog.db1"), locations(derived));
        assertEquals(HiveEntityDiffType.DELETE.name(), derived.get(0).getDiffType());
    }

    @Test
    void aRenameCarriesBothNames() {
        ResourceMappingDiff diff = MetastoreEntityDiffFactory.updateEntity(
            mapping("hive.db1.t1", HDFS_LOCATION),
            mapping("hive.db1.t2", "hdfs://nn:8020/warehouse/db1.db/t2"),
            HiveEntityType.TABLE, 8L);

        List<ResourceMappingDiff> derived = deriver.derive(diff);

        assertEquals(2, derived.size());
        assertEquals("hive_catalog.db1.t1", derived.get(0).getOldEntity().getLocation());
        assertEquals("hive.db1.t2", derived.get(0).getNewEntity().getName());
        assertEquals("hive_catalog.db1.t2", derived.get(0).getNewEntity().getLocation());
    }

    @Test
    void mappingsAreDerivedForEveryCatalogToo() {
        List<ResourceMapping> derived = deriver.derive(mapping("hive.db1.t1", HDFS_LOCATION));

        assertEquals(2, derived.size());
        assertEquals("hive.db1.t1", derived.get(0).getName(),
            "the Hive name must survive, it is what the policies are written against");
        assertEquals("hive_catalog.db1.t1", derived.get(0).getLocation());
        assertEquals("iceberg_catalog.db1.t1", derived.get(1).getLocation());
    }

    @Test
    void withoutCatalogsNothingIsDerived() {
        StarRocksResourceMappingDeriver empty =
            new StarRocksResourceMappingDeriver(Collections.emptyList());

        assertTrue(empty.derive(MetastoreEntityDiffFactory.createEntity(
            "hive.db1.t1", HiveEntityType.TABLE, HDFS_LOCATION, 1L)).isEmpty());
        assertTrue(empty.derive(mapping("hive.db1.t1", HDFS_LOCATION)).isEmpty());
    }

    // ---- diffs that are none of our business ----

    @Test
    void diffsOfAnotherSourceServiceAreIgnored() {
        assertTrue(deriver.derive(diff("other", "hdfs", "hive.db1.t1", HDFS_LOCATION)).isEmpty());
    }

    /** deriving from an already derived diff would loop */
    @Test
    void diffsAlreadyTargetingStarRocksAreIgnored() {
        assertTrue(deriver.derive(diff("hive", "starrocks", "hive.db1.t1", "hive_catalog.db1.t1")).isEmpty());
    }

    // ---- malformed entity names ----

    @ParameterizedTest(name = "name [{0}]")
    @ValueSource(strings = {"db1", "hive", "hive..t1", "hive.db1.", ".db1.t1", ""})
    void malformedEntityNamesAreSkipped(String name) {
        assertTrue(deriver.derive(
            MetastoreEntityDiffFactory.createEntity(name, HiveEntityType.TABLE, HDFS_LOCATION, 1L)).isEmpty());
        assertTrue(deriver.derive(mapping(name, HDFS_LOCATION)).isEmpty());
    }

    /** a rename whose new name cannot be derived must not produce a half-derived diff */
    @Test
    void aRenameWithAMalformedNewNameIsSkipped() {
        ResourceMappingDiff diff = MetastoreEntityDiffFactory.updateEntity(
            mapping("hive.db1.t1", HDFS_LOCATION),
            mapping("t2", "hdfs://nn:8020/warehouse/db1.db/t2"),
            HiveEntityType.TABLE, 8L);

        assertTrue(deriver.derive(diff).isEmpty());
    }

    // ---- toStarRocksName ----

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"hive", "hive..t1", ""})
    void toStarRocksNameRejectsWhatItCannotSplit(String name) {
        assertFalse(StarRocksResourceMappingDeriver.toStarRocksName("cat", name).isPresent());
    }

    @Test
    void toStarRocksNameReplacesTheCatalogSegment() {
        assertEquals(Optional.of("cat.db1.t1"),
            StarRocksResourceMappingDeriver.toStarRocksName("cat", "hive.db1.t1"));
        assertEquals(Optional.of("cat.db1"),
            StarRocksResourceMappingDeriver.toStarRocksName("cat", "hive.db1"));
    }

    @Test
    void toStarRocksNameKeepsTheCatalogCase() {
        assertEquals(Optional.of("Sales.db1.t1"),
            StarRocksResourceMappingDeriver.toStarRocksName("Sales", "hive.db1.t1"));
    }

    // ---- helpers ----

    private static List<String> locations(List<ResourceMappingDiff> diffs) {
        return diffs.stream().map(diff -> diff.getOldEntity().getLocation()).collect(Collectors.toList());
    }

    private static ResourceMapping mapping(String name, String location) {
        return ResourceMapping.builder().name(name).location(location).build();
    }

    private static ResourceMappingDiff diff(String sourceService, String targetService,
                                            String name, String location) {
        return ResourceMappingDiff.builder()
            .id(1L)
            .entityType(HiveEntityType.TABLE.name())
            .diffType(HiveEntityDiffType.CREATE.name())
            .sourceService(sourceService)
            .targetService(targetService)
            .oldEntity(mapping(name, location))
            .build();
    }
}
