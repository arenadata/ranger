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

package org.apache.ranger.hive.chained.starrocks;

import static org.apache.ranger.hive.chained.starrocks.ConfigMappings.ACCESS_MAPPINGS_KEY_TEMPLATE;
import static org.apache.ranger.hive.chained.starrocks.ConfigMappings.COLUMN_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.starrocks.ConfigMappings.DATABASE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.starrocks.ConfigMappings.TABLE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.plugin.policyengine.RangerPolicyEngine.ANY_ACCESS;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.hive.chained.mapping.HiveMappingFetcher;
import org.apache.ranger.hive.chained.mapping.HiveResourceMappingStore;
import org.apache.ranger.hive.chained.plugin.HiveChainedPlugin;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.service.RangerBasePlugin;

/**
 * Hive chained plugin for the StarRocks FE Ranger plugin.
 *
 * <p>StarRocks resources of external (Hive, Iceberg, ...) catalogs are mapped to Hive resources
 * with the {@code hive -> starrocks} mappings published by RMM: a mapping location is the
 * StarRocks name of a Hive database or table ({@code catalog.database[.table]}). The Hive
 * policies matched for the mapped resource decide the access to the StarRocks resource.
 * StarRocks resources without a mapping (internal catalog, unmapped catalogs, non-table
 * resources) are left to the native StarRocks policy evaluation.
 *
 * <p>Registration in {@code ranger-starrocks-security.xml}:
 * <pre>
 * ranger.plugin.starrocks.chained.services=hive
 * ranger.plugin.starrocks.chained.services.hive.impl=
 *     org.apache.ranger.hive.chained.starrocks.StarRocksHiveChainedPlugin
 * </pre>
 */
@Slf4j
public class StarRocksHiveChainedPlugin extends HiveChainedPlugin {
    private final Map<HiveObjectType, AccessMappings> accessTypeMappings;

    public StarRocksHiveChainedPlugin(RangerBasePlugin rootPlugin, String serviceName) throws IOException {
        super(rootPlugin, serviceName);

        this.accessTypeMappings = buildAccessTypeMappings(rootPlugin.getConfig());
    }

    @Override
    protected Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType) {
        return Optional.ofNullable(accessTypeMappings.get(hiveObjectType));
    }

    @Override
    protected Optional<String> getPathFromRequest(RangerAccessRequest request) {
        Optional<String> maybePath = StarRocksResourcePath.fromResource(request.getResource());
        if (!maybePath.isPresent()) {
            log.debug("StarRocks access request is not mappable to Hive: {}", request);
        }
        return maybePath;
    }

    @Override
    protected RangerHiveResource toHiveResource(HiveEntity entity, RangerAccessRequest request) {
        return StarRocksHiveResources.toHiveResource(entity, request.getResource());
    }

    @Override
    protected HiveMappingFetcher newMappingFetcher(RangerAdminClient adminClient,
                                                   HiveResourceMappingStore mappingStore,
                                                   long refreshInterval,
                                                   long mappingsPersistInterval,
                                                   String targetService) {
        return new StarRocksHiveMappingFetcher(
            adminClient, mappingStore, refreshInterval, mappingsPersistInterval, targetService);
    }

    private Map<HiveObjectType, AccessMappings> buildAccessTypeMappings(RangerPluginConfig config) {
        Map<HiveObjectType, AccessMappings> mappings = new HashMap<>();
        mappings.put(HiveObjectType.DATABASE,
            buildAccessMappings(config, HiveObjectType.DATABASE, DATABASE_ACCESS_MAPPINGS_DEFAULT));
        mappings.put(HiveObjectType.TABLE,
            buildAccessMappings(config, HiveObjectType.TABLE, TABLE_ACCESS_MAPPINGS_DEFAULT));
        mappings.put(HiveObjectType.COLUMN,
            buildAccessMappings(config, HiveObjectType.COLUMN, COLUMN_ACCESS_MAPPINGS_DEFAULT));
        return mappings;
    }

    private AccessMappings buildAccessMappings(RangerPluginConfig config,
                                               HiveObjectType objectType,
                                               Map<String, HiveAccessType[]> defaultMappings) {
        Map<String, List<HiveAccessType>> mappings = new HashMap<>();
        mappings.put(ANY_ACCESS, Collections.singletonList(HiveAccessType.USE));
        for (Map.Entry<String, HiveAccessType[]> entry : defaultMappings.entrySet()) {
            mappings.put(entry.getKey(), getAccessMappings(config, objectType, entry.getKey(), entry.getValue()));
        }

        log.debug("Hive {} access type mappings : {}", objectType, mappings);
        return new AccessMappings(mappings);
    }

    private List<HiveAccessType> getAccessMappings(RangerPluginConfig config,
                                                   HiveObjectType objectType,
                                                   String accessType,
                                                   HiveAccessType... defaultMappings) {
        String configKey = config.getPropertyPrefix() + String.format(ACCESS_MAPPINGS_KEY_TEMPLATE,
            objectType.toString().toLowerCase(Locale.ENGLISH), toConfigKeySuffix(accessType));
        String[] defaults = Arrays.stream(defaultMappings)
            .map(HiveAccessType::name)
            .toArray(String[]::new);
        return getAccessMappings(config.getStrings(configKey, defaults));
    }

    /** StarRocks multi-word access types ({@code create table}) are configured with underscores. */
    static String toConfigKeySuffix(String accessType) {
        return accessType.trim().toLowerCase(Locale.ENGLISH).replace(' ', '_');
    }
}
