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

import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.DEFAULT_LOCATION_SCHEME;
import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.HIVE_SERVICE;
import static org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType.UPDATE;

import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

public class ResourceMappingDiffFactory {
    public static ResourceMappingDiff diff(
        long id,
        HiveEntityType entityType,
        HiveEntityDiffType diffType,
        String name,
        String location
    ) {
        return diffBuilder(id, entityType, diffType)
            .oldEntity(new ResourceMapping(name, location))
            .build();
    }

    public static ResourceMappingDiff updateDiff(
        long id,
        HiveEntityType entityType,
        ResourceMapping oldMapping,
        ResourceMapping newMapping
    ) {
        return diffBuilder(id, entityType, UPDATE)
            .oldEntity(oldMapping)
            .newEntity(newMapping)
            .build();
    }

    public static ResourceMappingDiff.ResourceMappingDiffBuilder diffBuilder(
        long id, HiveEntityType entityType, HiveEntityDiffType diffType) {
        return ResourceMappingDiff.builder()
            .id(id)
            .sourceService(HIVE_SERVICE)
            .targetService(DEFAULT_LOCATION_SCHEME)
            .entityType(entityType.toString())
            .diffType(diffType.toString());
    }

}
