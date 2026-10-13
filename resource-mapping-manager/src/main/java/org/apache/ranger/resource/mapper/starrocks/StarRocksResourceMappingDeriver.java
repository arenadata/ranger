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

package org.apache.ranger.resource.mapper.starrocks;

import static org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory.HIVE_SERVICE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDeriver;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.model.ResourceMappingDiff;

/**
 * Derives {@code hive -> starrocks} mappings from Hive entity diffs. A Hive entity named
 * {@code <hms catalog>.<database>[.<table>]} is addressable in a StarRocks external catalog
 * backed by the same Hive Metastore as {@code <starrocks catalog>.<database>[.<table>]}; that
 * StarRocks name is used as the mapping location. One mapping is derived per configured catalog.
 */
@Slf4j
public class StarRocksResourceMappingDeriver implements ResourceMappingDeriver {
    public static final String STARROCKS_SERVICE = "starrocks";
    public static final String NAME_SEPARATOR = ".";

    private static final String NAME_SEPARATOR_REGEX = "\\.";
    /** Hive entity names carry the HMS catalog as the first segment. */
    private static final int HMS_CATALOG_SEGMENT = 0;

    private final List<String> catalogs;

    public StarRocksResourceMappingDeriver(List<String> catalogs) {
        this.catalogs = Collections.unmodifiableList(new ArrayList<>(catalogs));
    }

    @Override
    public List<ResourceMappingDiff> derive(ResourceMappingDiff diff) {
        if (!HIVE_SERVICE.equals(diff.getSourceService()) || STARROCKS_SERVICE.equals(diff.getTargetService())) {
            return Collections.emptyList();
        }

        List<ResourceMappingDiff> derived = new ArrayList<>(catalogs.size());
        for (String catalog : catalogs) {
            Optional<ResourceMapping> oldEntity = toStarRocksMapping(catalog, diff.getOldEntity());
            if (!oldEntity.isPresent()) {
                continue;
            }
            Optional<ResourceMapping> newEntity = Optional.ofNullable(diff.getNewEntity())
                .flatMap(entity -> toStarRocksMapping(catalog, entity));
            if (diff.getNewEntity() != null && !newEntity.isPresent()) {
                continue;
            }

            derived.add(ResourceMappingDiff.builder()
                .id(diff.getId())
                .entityType(diff.getEntityType())
                .diffType(diff.getDiffType())
                .sourceService(diff.getSourceService())
                .targetService(STARROCKS_SERVICE)
                .oldEntity(oldEntity.get())
                .newEntity(newEntity.orElse(null))
                .build());
        }
        return derived;
    }

    @Override
    public List<ResourceMapping> derive(ResourceMapping mapping) {
        List<ResourceMapping> derived = new ArrayList<>(catalogs.size());
        for (String catalog : catalogs) {
            toStarRocksMapping(catalog, mapping).ifPresent(derived::add);
        }
        return derived;
    }

    private Optional<ResourceMapping> toStarRocksMapping(String catalog, ResourceMapping hiveMapping) {
        return toStarRocksName(catalog, hiveMapping.getName())
            .map(location -> ResourceMapping.builder()
                .name(hiveMapping.getName())
                .location(location)
                .build());
    }

    /**
     * @return {@code <catalog>.<database>[.<table>]} for the Hive entity name
     *         {@code <hms catalog>.<database>[.<table>]}, or empty for a malformed name
     */
    static Optional<String> toStarRocksName(String catalog, String hiveEntityName) {
        if (hiveEntityName == null) {
            return Optional.empty();
        }
        List<String> segments = Arrays.asList(hiveEntityName.split(NAME_SEPARATOR_REGEX, -1));
        if (segments.size() < 2 || segments.stream().anyMatch(String::isEmpty)) {
            log.warn("Skipping StarRocks mapping for Hive entity with unexpected name: {}", hiveEntityName);
            return Optional.empty();
        }

        List<String> starRocksSegments = new ArrayList<>(segments.size());
        starRocksSegments.add(catalog);
        starRocksSegments.addAll(segments.subList(HMS_CATALOG_SEGMENT + 1, segments.size()));
        return Optional.of(String.join(NAME_SEPARATOR, starRocksSegments));
    }
}
