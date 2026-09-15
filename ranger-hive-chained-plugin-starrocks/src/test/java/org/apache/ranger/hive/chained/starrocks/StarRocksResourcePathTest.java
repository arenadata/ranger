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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StarRocksResourcePathTest {

    @Test
    void testPathFromTableResource() {
        assertEquals(Optional.of("/hive_catalog/db1/t1"),
            StarRocksResourcePath.fromResource(resource("hive_catalog", "db1", "t1")));
    }

    @Test
    void testPathFromColumnResourceIsLowerCased() {
        assertEquals(Optional.of("/hive_catalog/db1/t1/c1"),
            StarRocksResourcePath.fromResource(resource("Hive_Catalog", "DB1", "T1", "C1")));
    }

    @Test
    void testPathFromDatabaseResource() {
        assertEquals(Optional.of("/hive_catalog/db1"),
            StarRocksResourcePath.fromResource(resource("hive_catalog", "db1")));
    }

    @Test
    void testCatalogOnlyResourceIsNotMappable() {
        assertFalse(StarRocksResourcePath.fromResource(resource("hive_catalog")).isPresent());
    }

    @Test
    void testNonTableResourcesAreNotMappable() {
        assertFalse(StarRocksResourcePath.fromResource(resource(singletonMap("system", "*"))).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(resource(singletonMap("global_function", "f"))).isPresent());

        Map<String, Object> view = new HashMap<>();
        view.put("catalog", "default_catalog");
        view.put("database", "db1");
        view.put("view", "v1");
        assertFalse(StarRocksResourcePath.fromResource(resource(view)).isPresent());

        assertFalse(StarRocksResourcePath.fromResource(null).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(new RangerAccessResourceImpl()).isPresent());
    }

    @Test
    void testResourceWithHierarchyGapIsNotMappable() {
        Map<String, Object> noDatabase = new HashMap<>();
        noDatabase.put("catalog", "hive_catalog");
        noDatabase.put("table", "t1");
        assertFalse(StarRocksResourcePath.fromResource(resource(noDatabase)).isPresent());

        Map<String, Object> blankDatabase = new HashMap<>();
        blankDatabase.put("catalog", "hive_catalog");
        blankDatabase.put("database", " ");
        assertFalse(StarRocksResourcePath.fromResource(resource(blankDatabase)).isPresent());
    }

    @Test
    void testSegmentsKeepOriginalCase() {
        assertEquals(Optional.of(Arrays.asList("Hive_Catalog", "DB1", "T1")),
            StarRocksResourcePath.segments(resource("Hive_Catalog", "DB1", "T1")));
    }

    @ParameterizedTest
    @CsvSource({
        "hive_catalog.db1.t1, /hive_catalog/db1/t1",
        "Hive_Catalog.DB1, /hive_catalog/db1",
        "/hive_catalog/db1/t1, /hive_catalog/db1/t1",
        "/hive_catalog/db1/, /hive_catalog/db1",
        " hive_catalog.db1 , /hive_catalog/db1"
    })
    void testPathFromLocation(String location, String expectedPath) {
        assertEquals(expectedPath, StarRocksResourcePath.fromLocation(location));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "hive_catalog..t1", "hive_catalog.db1.", "/"})
    void testMalformedLocation(String location) {
        assertThrows(IllegalArgumentException.class, () -> StarRocksResourcePath.fromLocation(location));
    }

    @Test
    void testConfigKeySuffix() {
        assertEquals("create_table", StarRocksHiveChainedPlugin.toConfigKeySuffix("create table"));
        assertEquals("select", StarRocksHiveChainedPlugin.toConfigKeySuffix("SELECT "));
    }

    static RangerAccessResource resource(String... levels) {
        Map<String, Object> elements = new HashMap<>();
        for (int i = 0; i < levels.length; i++) {
            elements.put(StarRocksResourcePath.HIERARCHY.get(i), levels[i]);
        }
        return resource(elements);
    }

    static RangerAccessResource resource(Map<String, Object> elements) {
        return new RangerAccessResourceImpl(elements);
    }

    private static Map<String, Object> singletonMap(String key, String value) {
        Map<String, Object> elements = new HashMap<>();
        elements.put(key, value);
        return elements;
    }
}
