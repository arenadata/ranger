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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SynchronizedTrie<K, V> implements Trie<K, V> {
    private final Trie<K, V> delegate;
    private final ReadWriteLock lock;

    public SynchronizedTrie(Trie<K, V> delegate) {
        this.delegate = delegate;
        this.lock = new ReentrantReadWriteLock();
    }

    @Override
    public List<V> getPrefixValues(Key<K> key) {
        lock.readLock().lock();
        try {
            return delegate.getPrefixValues(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<V> get(Key<K> key) {
        lock.readLock().lock();
        try {
            return delegate.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(Key<K> key, V value) {
        lock.writeLock().lock();
        try {
            delegate.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(Key<K> key) {
        lock.writeLock().lock();
        try {
            return delegate.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean move(Key<K> oldKey, Key<K> newKey, V newValue) {
        lock.writeLock().lock();
        try {
            return delegate.move(oldKey, newKey, newValue);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void accept(TrieVisitor<K, V> visitor) throws Exception {
        lock.readLock().lock();
        try {
            delegate.accept(visitor);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static <K, V> Trie<K, V> wrap(Trie<K, V> delegate) {
        return new SynchronizedTrie<>(delegate);
    }
}
