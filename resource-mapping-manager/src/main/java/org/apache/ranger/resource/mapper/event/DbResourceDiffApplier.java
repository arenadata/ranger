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
import org.apache.ranger.resource.mapper.event.retry.RetryException;
import org.apache.ranger.resource.mapper.event.retry.RetrySupport;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

@Slf4j
@RequiredArgsConstructor
public class DbResourceDiffApplier implements ResourceDiffApplier {
    private final ResourceMappingDiffDao resourceMappingDiffDao;
    private final RetrySupport retrySupport;

    public void applyRecordsFrom(BlockingQueue<ResourceDiffStreamRecord> eventQueue) throws Exception {
        ResourceDiffStreamRecord event = null;

        while (event == null || !event.isLastRecord()) {
            try {
                event = eventQueue.take();
                ResourceDiffStreamRecord lastEvent = event;
                retrySupport.withRetries(() -> handleEvent(lastEvent));
            } catch (RetryException exception) {
                log.error("Error handling event", exception);
                throw exception;
            }
        }
    }

    private void handleEvent(ResourceDiffStreamRecord event) {
        if (event instanceof ResourceMappingDiff) {
            resourceMappingDiffDao.insert((ResourceMappingDiff) event);
        } else {
            log.warn("Unsupported event type: {}", event.getClass().getName());
        }
    }
}
