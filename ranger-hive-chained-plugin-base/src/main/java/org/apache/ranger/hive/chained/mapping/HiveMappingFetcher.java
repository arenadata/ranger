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

import static org.apache.ranger.hive.chained.plugin.HiveChainedPlugin.HIVE_SERVICE_TYPE;

import java.util.Objects;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.plugin.mapping.BaseResourceMappingFetcher;
import org.apache.ranger.plugin.model.ResourceMapping;
import org.apache.ranger.plugin.model.ResourceMappingDiff;

@Slf4j
public class HiveMappingFetcher extends BaseResourceMappingFetcher {

    enum HiveEntityDiffType {
        CREATE,
        UPDATE,
        DELETE
    }

    private final HiveResourceMappingStore mappingStore;

    public HiveMappingFetcher(
        RangerAdminClient adminClient,
        HiveResourceMappingStore mappingStore,
        long refreshInterval,
        long mappingsFlushInterval,
        String targetService
    ) {
        super(
            adminClient,
            Executors.newSingleThreadScheduledExecutor(),
            mappingStore,
            refreshInterval,
            mappingsFlushInterval,
            HIVE_SERVICE_TYPE,
            targetService
        );
        this.mappingStore = mappingStore;
    }

    public void applyDiff(ResourceMappingDiff diff) {
        switch (HiveEntityDiffType.valueOf(diff.getDiffType())) {
            case CREATE:
                handleCreateEntity(diff);
                return;
            case UPDATE:
                handleUpdateEntity(diff);
                return;
            case DELETE:
                handleDeleteEntity(diff);
        }
    }

    @Override
    protected void handleDiffApplyError(Exception error) {
        log.error("Failed to apply resource mapping changes", error);
    }

    @Override
    protected void handleCommitError(Exception error) {
        log.error("Failed to commit resource mapping changes", error);
    }

    protected ResourceMapping transformMapping(ResourceMapping mapping) {
        return mapping;
    }

    private void handleCreateEntity(ResourceMappingDiff diff) {
        if (diff.getOldEntity() == null) {
            throw new IllegalArgumentException("Wrong diff for create event: " + diff);
        }

        putEntity(transformMapping(diff.getOldEntity()), diff.getEntityType());
    }

    private void handleUpdateEntity(ResourceMappingDiff diff) {
        if (diff.getOldEntity() == null || diff.getNewEntity() == null) {
            throw new IllegalArgumentException("Wrong diff for update event: " + diff);
        }

        ResourceMapping oldEntity = transformMapping(diff.getOldEntity());
        ResourceMapping newEntity = transformMapping(diff.getNewEntity());

        if (Objects.equals(oldEntity.getLocation(), newEntity.getLocation())) {
            putEntity(newEntity, diff.getEntityType());
            return;
        }

        mappingStore.move(
            oldEntity.getLocation(),
            newEntity.getLocation(),
            toHiveEntity(newEntity, diff.getEntityType())
        );
    }

    private void handleDeleteEntity(ResourceMappingDiff diff) {
        if (diff.getOldEntity() == null) {
            throw new IllegalArgumentException("Wrong diff for delete event: " + diff);
        }

        mappingStore.remove(transformMapping(diff.getOldEntity()).getLocation());
    }

    private void putEntity(ResourceMapping mapping, String entityType) {
        HiveEntity hiveEntity = toHiveEntity(mapping, entityType);
        mappingStore.put(mapping.getLocation(), hiveEntity);
    }

    private HiveEntity toHiveEntity(ResourceMapping mapping, String entityType) {
        return new HiveEntity(
            mapping.getName(),
            HiveObjectType.valueOf(entityType)
        );
    }
}
