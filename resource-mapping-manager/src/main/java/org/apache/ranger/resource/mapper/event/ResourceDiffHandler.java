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

package org.apache.ranger.resource.mapper.event;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;

@Slf4j
@RequiredArgsConstructor
public class ResourceDiffHandler implements AutoCloseable {
    private final ResourceDiffSource resourceDiffSource;
    private final ResourceDiffCollector resourceDiffCollector;
    private final ResourceMappingDiffDao diffDao;

    public void start(boolean fullSync) throws Exception {
        Optional<Long> latestDiffId = diffDao.getLatestExternalDiffId(
            resourceDiffSource.getServiceName()
        );

        BlockingQueue<ResourceDiffStreamRecord> recordsDiffQueue;
        if (fullSync) {
            log.info("Running full resync of resource diffs");
            // if the full resync is required then restart fetcher from scratch
            diffDao.deleteAllDiffs();
            recordsDiffQueue = resourceDiffSource.pollAllAsync();
        } else if (latestDiffId.isPresent()) {
            log.info("Start fetching resource diffs from id {}", latestDiffId.get());
            // start from the last valid handled diff id
            recordsDiffQueue = resourceDiffSource.pollAsync(latestDiffId.get());
        } else {
            log.info("No last handled resource diff id is found. " +
                "Fetching resource diffs from scratch");
            // if there is no already handled diff id
            // and no restart is required then start from scratch
            recordsDiffQueue = resourceDiffSource.pollAllAsync();
        }

        resourceDiffCollector.collect(recordsDiffQueue);
    }

    @Override
    public void close() {
        try {
            resourceDiffSource.close();
        } catch (Exception exception) {
            log.error("Error closing event fetcher", exception);
        }
    }
}
