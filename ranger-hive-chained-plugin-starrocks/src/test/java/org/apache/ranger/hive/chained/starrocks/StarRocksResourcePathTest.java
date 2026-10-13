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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every shape a StarRocks resource or an RMM location can take, and what path it maps to. */
class StarRocksResourcePathTest {

    // ---- resources that map to a path ----

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "hive_catalog,db1                | /hive_catalog/db1",
        "hive_catalog,db1,t1             | /hive_catalog/db1/t1",
        "hive_catalog,db1,t1,c1          | /hive_catalog/db1/t1/c1",
    })
    void pathOfEveryMappableDepth(String levels, String expected) {
        assertEquals(Optional.of(expected), StarRocksResourcePath.fromResource(resource(levels.trim().split(","))));
    }

    /**
     * StarRocks resolves catalog names case-sensitively and two catalogs differing only in case may
     * be backed by different metastores, so the catalog must reach the mapping store untouched.
     */
    @Test
    void catalogKeepsItsCase() {
        assertEquals(Optional.of("/Sales/db1/t1"),
            StarRocksResourcePath.fromResource(resource("Sales", "db1", "t1")));
        assertEquals(Optional.of("/sales/db1/t1"),
            StarRocksResourcePath.fromResource(resource("sales", "db1", "t1")));
        assertNotEquals(StarRocksResourcePath.fromResource(resource("Sales", "db1", "t1")),
            StarRocksResourcePath.fromResource(resource("sales", "db1", "t1")));
    }

    /** Hive matches databases, tables and columns case-insensitively, so those are folded. */
    @Test
    void everythingBelowTheCatalogIsLowerCased() {
        assertEquals(Optional.of("/Hive_Catalog/db1/t1/c1"),
            StarRocksResourcePath.fromResource(resource("Hive_Catalog", "DB1", "T1", "C1")));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals(Optional.of("/hive_catalog/db1/t1"),
            StarRocksResourcePath.fromResource(resource(" hive_catalog ", " DB1 ", " t1 ")));
    }

    // ---- resources that do not map ----

    @Test
    void catalogAloneIsNotMappable() {
        assertFalse(StarRocksResourcePath.fromResource(resource("hive_catalog")).isPresent());
    }

    @ParameterizedTest(name = "resource key {0}")
    @ValueSource(strings = {"system", "user", "view", "materialized_view", "function",
        "global_function", "resource", "resource_group", "storage_volume", "pipe"})
    void resourcesOutsideTheTableHierarchyAreNotMappable(String key) {
        assertFalse(StarRocksResourcePath.fromResource(resource(singleton(key, "any"))).isPresent());
    }

    /** a view lives under catalog/database, but it is not a table and has no Hive mapping */
    @Test
    void aKnownPrefixWithAnUnknownLeafIsNotMappable() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put(StarRocksResourcePath.KEY_CATALOG, "hive_catalog");
        view.put(StarRocksResourcePath.KEY_DATABASE, "db1");
        view.put("view", "v1");
        assertFalse(StarRocksResourcePath.fromResource(resource(view)).isPresent());
    }

    @Test
    void holesInTheHierarchyAreNotMappable() {
        assertFalse(StarRocksResourcePath.fromResource(
            resource(pairs(StarRocksResourcePath.KEY_CATALOG, "hive_catalog",
                StarRocksResourcePath.KEY_TABLE, "t1"))).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(
            resource(pairs(StarRocksResourcePath.KEY_CATALOG, "hive_catalog",
                StarRocksResourcePath.KEY_COLUMN, "c1"))).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(
            resource(pairs(StarRocksResourcePath.KEY_CATALOG, "hive_catalog",
                StarRocksResourcePath.KEY_DATABASE, "db1",
                StarRocksResourcePath.KEY_COLUMN, "c1"))).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(
            resource(pairs(StarRocksResourcePath.KEY_DATABASE, "db1",
                StarRocksResourcePath.KEY_TABLE, "t1"))).isPresent());
    }

    @ParameterizedTest(name = "blank level {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    void aBlankLevelIsNotMappable(int blankLevel) {
        String[] levels = {"hive_catalog", "db1", "t1", "c1"};
        levels[blankLevel] = "   ";
        assertFalse(StarRocksResourcePath.fromResource(resource(levels)).isPresent());
    }

    @Test
    void emptyAndNullResourcesAreNotMappable() {
        assertFalse(StarRocksResourcePath.fromResource(null).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(new RangerAccessResourceImpl()).isPresent());
        assertFalse(StarRocksResourcePath.fromResource(resource(new LinkedHashMap<>())).isPresent());
    }

    // ---- segments() ----

    @Test
    void segmentsKeepTheOriginalCaseOfEveryLevel() {
        assertEquals(Optional.of(Arrays.asList("Hive_Catalog", "DB1", "T1")),
            StarRocksResourcePath.segments(resource("Hive_Catalog", "DB1", "T1")));
    }

    // ---- locations published by RMM ----

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "hive_catalog.db1            | /hive_catalog/db1",
        "hive_catalog.db1.t1         | /hive_catalog/db1/t1",
        "Hive_Catalog.DB1            | /Hive_Catalog/db1",
        "/hive_catalog/db1/t1        | /hive_catalog/db1/t1",
        "/hive_catalog/db1/          | /hive_catalog/db1",
        "hive_catalog/db1.t1         | /hive_catalog/db1/t1",
        "'  hive_catalog.db1  '      | /hive_catalog/db1",
        "hive_catalog. db1 . t1      | /hive_catalog/db1/t1",
        "hive_catalog                | /hive_catalog",
    })
    void locationIsNormalizedLikeAResource(String location, String expected) {
        assertEquals(expected, StarRocksResourcePath.fromLocation(location));
    }

    /** the two sides of the mapping must agree, or nothing is ever found in the store */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "hive_catalog | db1 | t1",
        "Sales        | DB1 | T1",
        "sales        | db1 | t1",
    })
    void locationAndResourceAgree(String catalog, String database, String table) {
        String fromLocation = StarRocksResourcePath.fromLocation(catalog + "." + database + "." + table);
        Optional<String> fromResource = StarRocksResourcePath.fromResource(resource(catalog, database, table));
        assertEquals(Optional.of(fromLocation), fromResource);
    }

    @ParameterizedTest(name = "malformed location [{0}]")
    @ValueSource(strings = {"  ", "/", "//", "hive_catalog..t1", "hive_catalog.db1.", ".db1.t1",
        "hive_catalog//db1", "hive_catalog. .t1", "."})
    void malformedLocationIsRejected(String location) {
        assertThrows(IllegalArgumentException.class, () -> StarRocksResourcePath.fromLocation(location));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void blankLocationIsRejected(String location) {
        assertThrows(IllegalArgumentException.class, () -> StarRocksResourcePath.fromLocation(location));
    }

    // ---- toPath ----

    @Test
    void toPathAppliesTheCaseRuleByPosition() {
        assertEquals("/Cat/db/tbl/col", StarRocksResourcePath.toPath(Arrays.asList("Cat", "DB", "TBL", "COL")));
    }

    // ---- helpers ----

    static RangerAccessResource resource(String... levels) {
        Map<String, Object> elements = new LinkedHashMap<>();
        for (int level = 0; level < levels.length; level++) {
            elements.put(StarRocksResourcePath.HIERARCHY.get(level), levels[level]);
        }
        return resource(elements);
    }

    static RangerAccessResource resource(Map<String, Object> elements) {
        return new RangerAccessResourceImpl(elements);
    }

    private static Map<String, Object> singleton(String key, String value) {
        Map<String, Object> elements = new HashMap<>();
        elements.put(key, value);
        return elements;
    }

    private static Map<String, Object> pairs(String... keysAndValues) {
        Map<String, Object> elements = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            elements.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return elements;
    }
}
