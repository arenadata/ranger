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

package org.apache.ranger.hive.chained.trie;

import static org.apache.ranger.hive.chained.mapping.TrieHiveResourceMappingStore.pathToTrieKey;
import static org.apache.ranger.hive.chained.mapping.TrieHiveResourceMappingStore.trieKeyToPath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TrieTest {
    private static Stream<Trie<String, String>> triesSource() {
        return Stream.of(
            new DefaultTrie<>(),
            SynchronizedTrie.wrap(new DefaultTrie<>())
        );
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testAdd(Trie<String, String> trie) {
        Trie.Key<String> key = pathToTrieKey("/root/dir/subdir");
        trie.put(key, "value");

        assertHasValue(trie, "/root/dir/subdir", "value");

        assertNoValue(trie, "/root/dir");
        assertNoValue(trie, "/root");
        assertNoValue(trie, "/");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testGet(Trie<String, String> trie) {
        Stream.of(
            "/root/dir/key1",
            "/root/dir/subdir/key2",
            "/other/sub/test/key3",
            "/key4",
            "other_key",
            "q",
            "/root/dir",
            "/root",
            "/other/sub/test"
        ).forEach(key -> putValueAndCheck(
            trie, key, UUID.randomUUID().toString()
        ));

        Stream.of(
            "/other",
            "/other/sub",
            "/unknown_dir",
            "/key40",
            "/key5"
        ).forEach(key -> assertFalse(
            trie.get(pathToTrieKey(key)).isPresent()
        ));
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testPut(Trie<String, String> trie) {
        putValueAndCheck(trie, "/root/dir/subdir", "value");
        putValueAndCheck(trie, "/root/dir/subdir", "value1");
        putValueAndCheck(trie, "/root/dir/subdir", "value2");
        putValueAndCheck(trie, "/root/dir/subdir", "other_value");
        putValueAndCheck(trie, "/root/dir/subdir", "another_val");

        putValueAndCheck(trie, "/root/dir2/key", "val");

        // recheck another key not affected
        assertHasValue(trie, "/root/dir/subdir", "another_val");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testRemove(Trie<String, String> trie) {
        putValueAndCheck(trie, "/root/subdir/key1", "value1");
        putValueAndCheck(trie, "/root/subdir/key2", "value2");
        putValueAndCheck(trie, "/root/subdir/key3", "value2");
        putValueAndCheck(trie, "/root/key4", "value3");
        putValueAndCheck(trie, "/key5", "value3");

        trie.remove(pathToTrieKey("/root/subdir/key2"));
        assertNoValue(trie, "/root/subdir/key2");

        assertHasValue(trie, "/root/subdir/key1", "value1");
        assertHasValue(trie, "/root/subdir/key3", "value2");
        assertHasValue(trie, "/root/key4", "value3");
        assertHasValue(trie, "/key5", "value3");

        trie.remove(pathToTrieKey("/root/subdir"));
        assertNoValue(trie, "/root/subdir/key2");
        assertNoValue(trie, "/root/subdir/key1");
        assertNoValue(trie, "/root/subdir/key3");

        assertHasValue(trie, "/root/key4", "value3");
        assertHasValue(trie, "/key5", "value3");

        // check has no effect
        trie.remove(pathToTrieKey("/roo"));
        assertNoValue(trie, "/root/subdir/key2");
        assertNoValue(trie, "/root/subdir/key1");
        assertNoValue(trie, "/root/subdir/key3");

        assertHasValue(trie, "/root/key4", "value3");
        assertHasValue(trie, "/key5", "value3");

        trie.remove(pathToTrieKey("/root"));
        assertNoValue(trie, "/root/subdir/key2");
        assertNoValue(trie, "/root/subdir/key1");
        assertNoValue(trie, "/root/subdir/key3");
        assertNoValue(trie, "/root/key4");

        assertHasValue(trie, "/key5", "value3");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testCleanupAfterRemove(Trie<String, String> trie) {
        putValueAndCheck(trie, "/root/subdir/key1", "value1");

        trie.remove(pathToTrieKey("/root/subdir/key1"));

        assertNoValue(trie, "/root/subdir/key1");
        assertNoValue(trie, "/root/subdir");
        assertNoValue(trie, "/root/");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testGetPrefix(Trie<String, String> trie) {
        putValueAndCheck(trie, "/root/subdir", "subdir_val");
        putValueAndCheck(trie, "/root/subdir/key", "key_val");
        putValueAndCheck(trie, "/root", "root_val");

        assertHasPrefixValues(trie, "/root/subdir/key/subkey", "root_val", "subdir_val", "key_val");
        assertHasPrefixValues(trie, "/root/subdir/key", "root_val", "subdir_val", "key_val");

        trie.remove(pathToTrieKey("/root/subdir/key"));
        assertHasPrefixValues(trie, "/root/subdir/key/subkey", "root_val", "subdir_val");
        assertHasPrefixValues(trie, "/root/subdir/key", "root_val", "subdir_val");

        trie.remove(pathToTrieKey("/root/subdir"));
        assertHasPrefixValues(trie, "/root/subdir/key/subkey", "root_val");
        assertHasPrefixValues(trie, "/root/subdir/key", "root_val");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testMove(Trie<String, String> trie) {
        putValueAndCheck(trie, "/root/dir/subdir/key1", "val1");
        putValueAndCheck(trie, "/root/dir/subdir/key2", "val2");
        putValueAndCheck(trie, "/root/dir/key3", "val3");

        trie.move(
            pathToTrieKey("/root/dir/subdir"),
            pathToTrieKey("/other_dir"),
            null
        );

        assertHasValue(trie, "/other_dir/key1", "val1");
        assertHasValue(trie, "/other_dir/key2", "val2");
        // check other key on the same level not affected
        assertHasValue(trie, "/root/dir/key3", "val3");

        assertNoValue(trie, "/root/dir/subdir/key1");
        assertNoValue(trie, "/root/dir/subdir/key2");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testMoveWithinSameParent(Trie<String, String> trie) {
        putValueAndCheck(trie, "/root/dir/subdir/key1", "val1");
        putValueAndCheck(trie, "/root/dir/subdir/key2", "val2");
        putValueAndCheck(trie, "/root/dir/key3", "val3");

        trie.move(
            pathToTrieKey("/root/dir/subdir"),
            pathToTrieKey("/root/dir/other_dir"),
            null
        );

        assertHasValue(trie, "/root/dir/other_dir/key1", "val1");
        assertHasValue(trie, "/root/dir/other_dir/key2", "val2");
        // check other key on the same level not affected
        assertHasValue(trie, "/root/dir/key3", "val3");

        assertNoValue(trie, "/root/dir/subdir/key1");
        assertNoValue(trie, "/root/dir/subdir/key2");
    }

    @ParameterizedTest
    @MethodSource(value = "triesSource")
    public void testAcceptVisitor(Trie<String, String> trie) throws Exception {
        Map<String, String> expectedPaths = ImmutableMap.<String, String>builder()
            .put("/key1", "val1")
            .put("/root/key2", "val2")
            .put("/root/dir/key3", "val3")
            .put("/root/dir/key4", "val4")
            .put("/root/dir/subdir/key5", "val5")
            .put("/root/another/key6", "val6")
            .put("/usr/key7", "val7")
            .build();

        expectedPaths.forEach((path, val) -> putValueAndCheck(trie, path, val));

        Map<String, String> actualPaths = new HashMap<>();
        trie.accept((key, entity) -> actualPaths.put(
            trieKeyToPath(key), entity
        ));
        assertEquals(expectedPaths, actualPaths);
    }

    private void putValueAndCheck(Trie<String, String> trie, String key, String value) {
        putValue(trie, key, value);
        assertHasValue(trie, key, value);
    }

    private void putValue(Trie<String, String> trie, String key, String value) {
        Trie.Key<String> trieKey = pathToTrieKey(key);
        trie.put(trieKey, value);
    }

    private void assertHasValue(Trie<String, String> trie, String key, String value) {
        Optional<String> maybeValue = trie.get(pathToTrieKey(key));
        assertEquals(value, maybeValue.orElse(null),
            "Wrong value for key: " + key);
    }

    private void assertHasPrefixValues(Trie<String, String> trie, String key, String... values) {
        List<String> maybeValue = trie.getPrefixValues(pathToTrieKey(key));

        assertThat("Wrong value for key: " + key,
            maybeValue, containsInAnyOrder(values));
    }

    private void assertNoValue(Trie<String, String> trie, String key) {
        Optional<String> rootValue = trie.get(pathToTrieKey(key));
        assertFalse(rootValue.isPresent());
    }
}