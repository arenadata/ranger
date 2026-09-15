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

package org.apache.ranger.resource.mapper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;

/**
 * {@link ResourceMappingDiffDao} decorator that stores, next to every diff, the diffs derived
 * for other target services by the configured {@link ResourceMappingDeriver}s. Deletions are
 * propagated to the derived mappings as well, so the derived targets stay consistent with the
 * original one.
 */
@Slf4j
public class DerivingResourceMappingDiffDao implements ResourceMappingDiffDao {
    private final ResourceMappingDiffDao delegate;
    private final List<ResourceMappingDeriver> derivers;

    public DerivingResourceMappingDiffDao(ResourceMappingDiffDao delegate,
                                          List<ResourceMappingDeriver> derivers) {
        this.delegate = delegate;
        this.derivers = new ArrayList<>(derivers);
    }

    @Override
    public void deleteDiffsFor(ResourceMapping mapping) {
        delegate.deleteDiffsFor(mapping);
        for (ResourceMappingDeriver deriver : derivers) {
            for (ResourceMapping derived : deriver.derive(mapping)) {
                log.debug("Deleting derived mapping {} of {}", derived, mapping);
                delegate.deleteDiffsFor(derived);
            }
        }
    }

    @Override
    public void deleteAllDiffs() throws Exception {
        delegate.deleteAllDiffs();
    }

    @Override
    public void insertDiff(ResourceMappingDiff entityDiff) {
        delegate.insertDiff(entityDiff);
        for (ResourceMappingDeriver deriver : derivers) {
            for (ResourceMappingDiff derived : deriver.derive(entityDiff)) {
                log.debug("Inserting derived diff {} of {}", derived, entityDiff);
                delegate.insertDiff(derived);
            }
        }
    }

    @Override
    public Optional<Long> getLatestExternalDiffId(String sourceServiceName) {
        return delegate.getLatestExternalDiffId(sourceServiceName);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        return delegate.execute(action);
    }
}
