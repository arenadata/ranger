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

package org.apache.ranger.hive.chained.mapping.persist;

import static org.apache.ranger.hive.chained.mapping.TrieHiveResourceMappingStore.pathToTrieKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableMap;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.hive.chained.trie.DefaultTrie;
import org.apache.ranger.hive.chained.trie.TrieVisitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemMappingsPersistentStoreTest {
    private FileSystemMappingsPersistentStore store;
    private DefaultTrie<String, HiveEntity> trie;

    private static final Map<String, HiveEntity> TEST_MAPPINGS = ImmutableMap
        .<String, HiveEntity>builder()
        .put("/key1", table("table1"))
        .put("/root/key2", table("table2"))
        .put("/root/dir", db("db1"))
        .put("/root/dir/key3", table("table3"))
        .put("/root/dir/key4", table("table4"))
        .put("/root/dir/subdir/key5", table("table5"))
        .put("/root/another/key6", table("table6"))
        .put("/usr/key7", db("db2"))
        .build();

    @TempDir
    protected Path testDir;

    @BeforeEach
    public void setUp() {
        Path mappingsFile = testDir.resolve(UUID.randomUUID().toString());
        store = new FileSystemMappingsPersistentStore(mappingsFile.toString());

        trie = new DefaultTrie<>();
        initTrie();
    }

    @Test
    public void testPersistAndLoad() throws Exception {
        persist(11L);

        HiveMappings expectedMappings = new HiveMappings(11L, TEST_MAPPINGS);
        Optional<HiveMappings> mappings = store.getMappings();

        assertTrue(mappings.isPresent());
        assertEquals(expectedMappings, mappings.get());
    }

    @Test
    public void testRewriteFile() throws Exception {
        persist(1L);
        persist(2L);
        persist(3L);

        HiveMappings expectedMappings = new HiveMappings(3L, TEST_MAPPINGS);
        Optional<HiveMappings> mappings = store.getMappings();

        assertTrue(mappings.isPresent());
        assertEquals(expectedMappings, mappings.get());

        trie.put(pathToTrieKey("/root/dir/key3"), table("table33"));
        trie.put(pathToTrieKey("/root/dir/key4"), db("table4"));
        persist(4L);

        Map<String, HiveEntity> newMappings = new HashMap<>(TEST_MAPPINGS);
        newMappings.put("/root/dir/key3", table("table33"));
        newMappings.put("/root/dir/key4", db("table4"));
        HiveMappings expectedUpdatedMappings = new HiveMappings(4L, newMappings);
        mappings = store.getMappings();

        assertTrue(mappings.isPresent());
        assertEquals(expectedUpdatedMappings, mappings.get());
    }

    @Test
    public void testPersistTrie() throws Exception {
        persist(10L);

        String expectedMappings = fileContent(resolveResourcePath("mappings/fs-test-mappings.txt"));
        String actualMappings = fileContent(store.getMappingsLocation());
        assertEquals(expectedMappings, actualMappings);
    }

    private String fileContent(String location) throws Exception {
        return new String(
            Files.readAllBytes(Paths.get(location)),
            StandardCharsets.UTF_8
        );
    }

    private String resolveResourcePath(String resourcePath) {
        URL resource = this.getClass().getClassLoader().getResource(resourcePath);
        return Optional.ofNullable(resource)
            .map(URL::getPath)
            .orElseThrow(() -> new IllegalArgumentException("File not found: " + resourcePath));
    }

    private void persist(long version) throws Exception {
        try (TrieVisitor<String, HiveEntity> visitor = store.getTrieWriter(version)) {
            trie.accept(visitor);
        }
    }

    private void initTrie() {
        TEST_MAPPINGS.forEach((key, val) ->
            trie.put(pathToTrieKey(key), val)
        );
    }

    private static HiveEntity table(String name) {
        return new HiveEntity(name, HiveObjectType.TABLE);
    }

    private static HiveEntity db(String name) {
        return new HiveEntity(name, HiveObjectType.DATABASE);
    }
}