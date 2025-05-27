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

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.ranger.hive.chained.mapping.persist.FileSystemMappingsPersistentStore;
import org.apache.ranger.hive.chained.mapping.persist.HiveMappings;
import org.apache.ranger.hive.chained.mapping.persist.TriePersistentStore;
import org.apache.ranger.hive.chained.trie.DefaultTrie;
import org.apache.ranger.hive.chained.trie.SynchronizedTrie;
import org.apache.ranger.hive.chained.trie.Trie;
import org.apache.ranger.hive.chained.trie.TrieVisitor;

@RequiredArgsConstructor
public class TrieHiveResourceMappingStore implements HiveResourceMappingStore {

    private static final Trie.Key<String> ROOT_KEY = new Trie.Key<>(new String[0]);
    private static final String ROOT_PATH = "/";
    private static final String PATH_DELIMITER = "/";

    private final Trie<String, HiveEntity> pathPrefixes;
    private final TriePersistentStore persistentStore;
    private final Long loadedVersion;

    public TrieHiveResourceMappingStore() {
        this.pathPrefixes = SynchronizedTrie.wrap(new DefaultTrie<>());

        this.persistentStore = null;
        this.loadedVersion = null;
    }

    public TrieHiveResourceMappingStore(String mappingsLocation) throws IOException {
        this.persistentStore = new FileSystemMappingsPersistentStore(mappingsLocation);
        this.pathPrefixes = SynchronizedTrie.wrap(new DefaultTrie<>());

        this.loadedVersion = loadMappings().orElse(null);
    }

    @Override
    public Optional<HiveEntity> get(String path) {
        List<HiveEntity> prefixValues = pathPrefixes.getPrefixValues(pathToTrieKey(path));
        return Optional.of(prefixValues)
            .filter(entities -> !entities.isEmpty())
            .map(entities -> entities.get(entities.size() - 1));
    }

    @Override
    public void put(String path, HiveEntity value) {
        pathPrefixes.put(pathToTrieKey(path), value);
    }

    @Override
    public void remove(String path) {
        pathPrefixes.remove(pathToTrieKey(path));
    }

    @Override
    public void move(String oldPath, String newPath, HiveEntity newValue) {
        pathPrefixes.move(pathToTrieKey(oldPath), pathToTrieKey(newPath), newValue);
    }

    @Override
    public void commit(long commitId) throws Exception {
        if (persistentStore == null) {
            return;
        }

        try (TrieVisitor<String, HiveEntity> visitor = persistentStore.getTrieWriter(commitId)) {
            pathPrefixes.accept(visitor);
        }
    }

    @Override
    public Optional<Long> getLastCommitId() {
        return Optional.ofNullable(loadedVersion);
    }

    public static Trie.Key<String> pathToTrieKey(String path) {
        if (path == null) {
            return null;
        }

        String formattedPath = path.trim();
        if (path.equals(ROOT_PATH)) {
            return ROOT_KEY;
        }

        int startTrimPosition = 0;
        if (formattedPath.startsWith(PATH_DELIMITER)) {
            ++startTrimPosition;
        }

        int endTrimPosition = formattedPath.length();
        if (formattedPath.endsWith(PATH_DELIMITER)) {
            --endTrimPosition;
        }

        return new Trie.Key<>(formattedPath
            .substring(startTrimPosition, endTrimPosition)
            .split(PATH_DELIMITER));
    }

    public static String trieKeyToPath(Trie.Key<String> key) {
        return PATH_DELIMITER + String.join(PATH_DELIMITER, key.getSegments());
    }

    private Optional<Long> loadMappings() throws IOException {
        if (persistentStore == null) {
            return Optional.empty();
        }

        Optional<HiveMappings> mappings = persistentStore.getMappings();
        mappings.ifPresent(
            hiveMappings -> hiveMappings.getMappings().forEach(this::put)
        );

        return mappings.map(HiveMappings::getVersion);
    }
}
