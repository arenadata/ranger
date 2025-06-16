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

import java.util.concurrent.BlockingQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;

@Slf4j
@RequiredArgsConstructor
public class ResourceDiffHandler implements AutoCloseable {
    public static final long INITIAL_DIFF_ID = 0L;

    private final ResourceDiffSource resourceDiffSource;
    private final ResourceDiffCollector resourceDiffCollector;
    private final ResourceMappingDiffDao diffDao;

    public void start() throws Exception {
        long latestDiffId = diffDao.getLatestDiffId(
            resourceDiffSource.getServiceName()
        ).orElse(INITIAL_DIFF_ID);
        BlockingQueue<ResourceDiffStreamRecord> recordsDiffQueue = resourceDiffSource.pollAsync(latestDiffId);
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
