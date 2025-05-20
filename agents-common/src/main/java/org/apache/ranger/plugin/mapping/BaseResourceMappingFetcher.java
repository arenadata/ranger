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

package org.apache.ranger.plugin.mapping;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.plugin.model.ResourceMappingDiff;
import org.apache.ranger.plugin.model.ResourceMappingDiffs;

public abstract class BaseResourceMappingFetcher implements ResourceMappingFetcher {
    private final RangerAdminClient adminClient;
    private final ScheduledExecutorService executor;
    private final ResourceMappingStore mappingStore;
    private final String sourceService;
    private final String targetService;
    private final long refreshInterval;
    private final long commitInterval;

    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    private volatile Long lastHandledDiffId;

    public BaseResourceMappingFetcher(RangerAdminClient adminClient,
                                      ScheduledExecutorService executor,
                                      ResourceMappingStore mappingStore,
                                      long refreshInterval,
                                      long commitInterval,
                                      String sourceService,
                                      String targetService) {
        this.adminClient = adminClient;
        this.sourceService = sourceService;
        this.targetService = targetService;
        this.executor = executor;
        this.mappingStore = mappingStore;
        this.refreshInterval = refreshInterval;
        this.commitInterval = commitInterval;
    }

    @Override
    public void start() {
        if (isStarted.compareAndSet(false, true)) {
            lastHandledDiffId = mappingStore.getLastCommitId().orElse(null);
            if (lastHandledDiffId == null) {
                initialFetch();
            }

            executor.scheduleAtFixedRate(
                this::fetchDiffs, 0L, refreshInterval, TimeUnit.MILLISECONDS);

            executor.scheduleAtFixedRate(
                this::commitDiffId, 0L, commitInterval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
    }

    protected void initialFetch() {
        fetchDiffs();
    }

    private void fetchDiffs() {
        try {
            ResourceMappingDiffs diffs = adminClient.getResourceMappingDiffs(
                sourceService,
                targetService,
                lastHandledDiffId
            );

            diffs.getDiffs().forEach(this::handle);
        } catch (Exception e) {
            handleDiffApplyError(e);
        }
    }

    private void commitDiffId() {
        if (lastHandledDiffId != null) {
            try {
                mappingStore.commit(lastHandledDiffId);
            } catch (Exception e) {
                handleCommitError(e);
            }
        }
    }

    private void handle(ResourceMappingDiff diff) {
        applyDiff(diff);
        lastHandledDiffId = diff.getId();
    }

    protected abstract void applyDiff(ResourceMappingDiff diff);

    protected abstract void handleDiffApplyError(Exception error);

    protected abstract void handleCommitError(Exception error);
}
