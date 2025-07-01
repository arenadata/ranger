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

import java.net.URI;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityDiffType;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

@Slf4j
public class MetastoreEntityDiffFactory {
    public static final String HIVE_SERVICE = "hive";
    public static final String DEFAULT_LOCATION_SCHEME = "file";
    public static final String HDFS_LOCATION_SCHEME = "hdfs";
    public static final String HDFS_SERVICE = "hdfs";
    public static final String OFS_LOCATION_SCHEME = "ofs";
    public static final String OZONE_SERVICE = "ozone";

    public static ResourceMappingDiff createEntity(
        String name,
        HiveEntityType type,
        String location,
        long eventId) {
        return ResourceMappingDiff.builder()
            .sourceService(HIVE_SERVICE)
            .targetService(extractService(location))
            .oldEntity(ResourceMapping.builder()
                .name(name)
                .location(location)
                .build())
            .diffType(HiveEntityDiffType.CREATE.name())
            .entityType(type.name())
            .id(eventId)
            .build();
    }

    public static ResourceMappingDiff dropEntity(
        String name,
        String location,
        HiveEntityType type,
        long eventId) {
        return ResourceMappingDiff.builder()
            .sourceService(HIVE_SERVICE)
            .targetService(extractService(location))
            .oldEntity(ResourceMapping.builder()
                .name(name)
                .location(location)
                .build())
            .diffType(HiveEntityDiffType.DELETE.name())
            .entityType(type.name())
            .id(eventId)
            .build();
    }

    public static ResourceMappingDiff updateEntity(
        ResourceMapping oldEntity,
        ResourceMapping newEntity,
        HiveEntityType type,
        long eventId) {
        return ResourceMappingDiff.builder()
            .sourceService(HIVE_SERVICE)
            .targetService(extractService(oldEntity.getLocation()))
            .oldEntity(oldEntity)
            .newEntity(newEntity)
            .diffType(HiveEntityDiffType.UPDATE.name())
            .entityType(type.name())
            .id(eventId)
            .build();
    }

    public static String extractService(String location) {
        return Optional.ofNullable(location)
            .map(URI::create)
            .map(URI::getScheme)
            .map(MetastoreEntityDiffFactory::schemeToService)
            .orElse(DEFAULT_LOCATION_SCHEME);
    }

    private static String schemeToService(String scheme) {
        switch (scheme) {
            case HDFS_LOCATION_SCHEME:
                return HDFS_SERVICE;
            case OFS_LOCATION_SCHEME:
                return OZONE_SERVICE;
            default:
                throw new IllegalArgumentException("Unknown location scheme " + scheme);
        }
    }
}
