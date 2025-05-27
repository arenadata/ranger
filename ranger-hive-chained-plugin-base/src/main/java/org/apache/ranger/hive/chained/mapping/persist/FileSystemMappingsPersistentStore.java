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

import static org.apache.ranger.hive.chained.mapping.TrieHiveResourceMappingStore.trieKeyToPath;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.hive.chained.trie.Trie;
import org.apache.ranger.hive.chained.trie.TrieVisitor;

@RequiredArgsConstructor
public class FileSystemMappingsPersistentStore implements TriePersistentStore {
    @Getter
    private final String mappingsLocation;
    private final MappingSerializer mappingSerializer;

    public FileSystemMappingsPersistentStore(String mappingsLocation) {
        this.mappingsLocation = mappingsLocation;
        this.mappingSerializer = new JsonMappingSerializer();
    }

    @Override
    public Optional<HiveMappings> getMappings() throws IOException {
        Path mappingsPath = Paths.get(mappingsLocation);
        if (!Files.exists(mappingsPath)) {
            return Optional.empty();
        }

        long version;
        Map<String, HiveEntity> mappings = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(mappingsPath, StandardCharsets.UTF_8)) {
            version = Long.parseLong(reader.readLine());
            reader.lines()
                .forEach(line -> mappingSerializer.deserialize(line, mappings));
        }

        return Optional.of(new HiveMappings(version, mappings));
    }

    @Override
    public TrieVisitor<String, HiveEntity> getTrieWriter(long version) throws IOException {
        return new TrieFileWriter(mappingsLocation, mappingSerializer, version);
    }

    public static class TrieFileWriter implements TrieVisitor<String, HiveEntity> {
        private final BufferedWriter writer;
        private final MappingSerializer mappingSerializer;

        public TrieFileWriter(
            String filePath,
            MappingSerializer mappingSerializer,
            long version
        ) throws IOException {
            this.writer = new BufferedWriter(new FileWriter(filePath));
            this.mappingSerializer = mappingSerializer;

            writer.write(String.valueOf(version));
            writer.newLine();
        }

        @Override
        public void acceptNode(Trie.Key<String> key, HiveEntity value) throws IOException {
            String serialize = mappingSerializer.serialize(trieKeyToPath(key), value);
            writer.write(serialize);
            writer.newLine();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
