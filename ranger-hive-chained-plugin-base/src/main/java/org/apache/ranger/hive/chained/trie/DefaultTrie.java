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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultTrie<K, V> implements Trie<K, V> {
    private final DefaultTrieNode<K, V> root;

    public DefaultTrie() {
        this.root = new DefaultTrieNode<>(null, null);
    }

    @Override
    public List<V> getPrefixValues(Key<K> key) {
        return getPrefixNodes(key)
            .stream()
            .map(DefaultTrieNode::getValue)
            .collect(Collectors.toList());
    }

    @Override
    public Optional<V> get(Key<K> key) {
        return getExactNode(key).map(DefaultTrieNode::getValue);
    }

    @Override
    public void put(Trie.Key<K> key, V value) {
        getOrCreateNode(key).setValue(value);
    }

    @Override
    public boolean remove(Trie.Key<K> key) {
        return removeNode(key).isPresent();
    }

    @Override
    public boolean move(Trie.Key<K> oldKey, Trie.Key<K> newKey, V newValue) {
        Optional<DefaultTrieNode<K, V>> node = removeNode(oldKey);
        if (!node.isPresent()) {
            return false;
        }

        DefaultTrieNode<K, V> newNode = getOrCreateNode(newKey);
        newNode.merge(node.get());
        if (newValue != null) {
            newNode.setValue(newValue);
        }
        return true;
    }

    @Override
    public void accept(TrieVisitor<K, V> visitor) throws Exception {
        root.accept(visitor);
    }

    private DefaultTrieNode<K, V> getOrCreateNode(Trie.Key<K> key) {
        DefaultTrieNode<K, V> iter = root;
        for (K segment : key.getSegments()) {
            iter = iter.getOrCreateChild(segment);
        }
        return iter;
    }

    private Optional<DefaultTrieNode<K, V>> removeNode(Trie.Key<K> key) {
        Optional<DefaultTrieNode<K, V>> maybeNode = getExactNode(key);
        if (!maybeNode.isPresent()) {
            return Optional.empty();
        }

        DefaultTrieNode<K, V> node = maybeNode.get();
        // physically remove node and parents if needed
        K previousKey = node.getKey();
        DefaultTrieNode<K, V> iter = node.getParent();
        while (iter != null) {
            iter.removeChild(previousKey);
            if (!iter.isLeaf()) {
                break;
            }

            iter = iter.getParent();
        }

        return Optional.of(node);
    }

    private Optional<DefaultTrieNode<K, V>> getExactNode(Trie.Key<K> key) {
        DefaultTrieNode<K, V> iter = root;
        for (K segment : key.getSegments()) {
            Optional<DefaultTrieNode<K, V>> child = iter.getChild(segment);
            if (!child.isPresent()) {
                return Optional.empty();
            }

            iter = child.get();
        }

        return Optional.ofNullable(iter);
    }

    private List<DefaultTrieNode<K, V>> getPrefixNodes(Trie.Key<K> key) {
        List<DefaultTrieNode<K, V>> results = new ArrayList<>();

        DefaultTrieNode<K, V> iter = root;
        for (K segment : key.getSegments()) {
            Optional<DefaultTrieNode<K, V>> child = iter.getChild(segment);
            if (!child.isPresent()) {
                break;
            }

            if (child.get().getValue() != null) {
                results.add(child.get());
            }

            iter = child.get();
        }

        return results;
    }
}
