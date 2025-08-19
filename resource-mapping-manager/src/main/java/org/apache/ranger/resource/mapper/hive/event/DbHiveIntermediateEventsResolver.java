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

package org.apache.ranger.resource.mapper.hive.event;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

/**
 * Entity to handle Hive metastore events happened after
 * the start and before the end of the fetching of all HMS entities.
 * We can't repeatedly save the same events in db due to some corner cases,
 * e.g. when we fetch the tables A and C from HMS and the event id before fetching entities is 1.
 * If the following events occurred between getting the start last event id and fetching entities,
 * then we will lose mapping for table A: DELETE A, RENAME B to A.
 * That's why we need some kind of materialized in-memory buffer,
 * that will handle such cases before modifying the resource mapping diff table.
 */
@Slf4j
public class DbHiveIntermediateEventsResolver implements HiveIntermediateEventsResolver {

    private final ResourceMappingDiffDao resourceMappingDiffDao;

    private final Map<ResourceMapping, ResourceMappingDiff> entitiesToCreate;
    private final Set<ResourceMapping> entitiesToDelete;

    public DbHiveIntermediateEventsResolver(ResourceMappingDiffDao resourceMappingDiffDao) {
        this.resourceMappingDiffDao = resourceMappingDiffDao;
        this.entitiesToCreate = new HashMap<>();
        this.entitiesToDelete = new HashSet<>();
    }

    public void handle(ResourceDiffStreamRecord record) {
        if (record.isLastRecord()) {
            return;
        }

        if (!(record instanceof ResourceMappingDiff)) {
            throw new IllegalArgumentException("Unexpected record type: " + record.getClass().getName());
        }

        ResourceMappingDiff diff = (ResourceMappingDiff) record;
        switch (HiveEntityDiffType.valueOf(diff.getDiffType())) {
            case CREATE:
                handleCreate(diff);
                break;
            case UPDATE:
                handleUpdate(diff);
                break;
            case DELETE:
                handleDelete(diff);
        }
    }

    public void flush() {
        resourceMappingDiffDao.executeWithoutResult(ignore -> flushAction());
    }

    private void flushAction() {
        for (ResourceMapping resourceMapping : entitiesToDelete) {
            log.info("Intermediate Hive event resolving: deleting mapping {}", resourceMapping);
            resourceMappingDiffDao.deleteDiffsFor(resourceMapping);
        }

        for (ResourceMappingDiff diff : entitiesToCreate.values()) {
            log.info("Intermediate Hive event resolving: inserting mapping {}", diff.getOldEntity());
            resourceMappingDiffDao.insertDiff(diff);
        }
    }

    private void handleCreate(ResourceMappingDiff diff) {
        entitiesToDelete.remove(diff.getOldEntity());
        entitiesToCreate.put(diff.getOldEntity(), diff);
    }

    private void handleUpdate(ResourceMappingDiff diff) {
        entitiesToCreate.remove(diff.getOldEntity());
        entitiesToDelete.add(diff.getOldEntity());

        ResourceMappingDiff createDiff = MetastoreEntityDiffFactory.createEntity(
            diff.getNewEntity().getName(),
            HiveEntityType.valueOf(diff.getEntityType()),
            diff.getNewEntity().getLocation(),
            diff.getId()
        );
        handleCreate(createDiff);
    }

    private void handleDelete(ResourceMappingDiff diff) {
        entitiesToCreate.remove(diff.getOldEntity());
        entitiesToDelete.add(diff.getOldEntity());
    }
}
