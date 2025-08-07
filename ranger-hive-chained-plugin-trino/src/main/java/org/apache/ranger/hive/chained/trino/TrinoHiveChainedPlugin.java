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

package org.apache.ranger.hive.chained.trino;

import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.hive.chained.plugin.BaseHiveChainedPlugin;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.policyengine.RangerResourceACLs;
import org.apache.ranger.plugin.service.RangerBasePlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Locale.ENGLISH;
import static org.apache.ranger.hive.chained.trino.ConfigMappings.ACCESS_MAPPINGS_KEY_TEMPLATE;
import static org.apache.ranger.hive.chained.trino.ConfigMappings.COLUMN_ACCESS_MAPPING_DEFAULT;
import static org.apache.ranger.hive.chained.trino.ConfigMappings.DATABASE_ACCESS_MAPPING_DEFAULT;
import static org.apache.ranger.hive.chained.trino.ConfigMappings.TABLE_ACCESS_MAPPING_DEFAULT;

@Slf4j
public class TrinoHiveChainedPlugin
        extends BaseHiveChainedPlugin
{
    private static final String HIVE_SERVICE_TYPE = "hive";
    private static final String TABLE_RESOURCE = "table";
    private static final String SCHEMA_RESOURCE = "schema";
    private static final String COLUMN_RESOURCE = "column";
    private static final Set<String> TRINO_RESOURCES =
            new HashSet<>(Arrays.asList(SCHEMA_RESOURCE, TABLE_RESOURCE, COLUMN_RESOURCE));
    private final Map<HiveObjectType, AccessMappings> accessTypeMappings;

    public TrinoHiveChainedPlugin(RangerBasePlugin rootPlugin, String serviceName)
    {
        super(rootPlugin, HIVE_SERVICE_TYPE, serviceName);
        this.accessTypeMappings = createMappings(rootPlugin.getConfig());
    }

    @Override
    public RangerResourceACLs getResourceACLs(RangerAccessRequest request)
    {
        // currently we don't need to override the root plugins ACLs
        return null;
    }

    @Override
    public RangerResourceACLs getResourceACLs(RangerAccessRequest request, Integer policyType)
    {
        // currently we don't need to override the root plugins ACLs
        return null;
    }

    @Override
    protected List<RangerAccessRequest> toChainedRequests(RangerAccessRequest request)
    {
        RangerAccessResource trinoResource = request.getResource();
        if (trinoResource.getKeys().stream().noneMatch(TRINO_RESOURCES::contains)) {
            return Collections.emptyList();
        }
        return toRangerHiveResource(trinoResource)
                .map(hiveResource -> toHiveAccessRequests(hiveResource, request))
                .orElseGet(Collections::emptyList);
    }

    private Optional<RangerHiveResource> toRangerHiveResource(RangerAccessResource trinoResource)
    {
        String schema = (String) trinoResource.getValue(SCHEMA_RESOURCE);
        List<String> objects = new ArrayList<>();
        objects.add(schema);
        Optional.ofNullable(trinoResource.getValue(TABLE_RESOURCE))
                .map(String.class::cast)
                .ifPresent(objects::add);
        Optional.ofNullable(trinoResource.getValue(COLUMN_RESOURCE))
                .map(String.class::cast)
                .ifPresent(objects::add);
        return createHiveResource(objects);
    }

    private Optional<RangerHiveResource> createHiveResource(List<String> objects)
    {
        RangerHiveResource hiveResource = null;
        if (objects.size() == 1) {
            hiveResource = new RangerHiveResource(HiveObjectType.DATABASE, objects.get(0));
        }
        else if (objects.size() == 2) {
            hiveResource = new RangerHiveResource(HiveObjectType.TABLE, objects.get(0), objects.get(1));
        }
        else if (objects.size() == 3) {
            hiveResource =
                    new RangerHiveResource(HiveObjectType.COLUMN, objects.get(0), objects.get(1), objects.get(2));
        }
        return Optional.ofNullable(hiveResource);
    }

    @Override
    protected Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType)
    {
        return Optional.ofNullable(accessTypeMappings.get(hiveObjectType));
    }

    private Map<HiveObjectType, AccessMappings> createMappings(RangerPluginConfig config)
    {
        Map<HiveObjectType, AccessMappings> result = new HashMap<>();
        result.put(HiveObjectType.DATABASE, buildAccessMapping(config, HiveObjectType.DATABASE,
                DATABASE_ACCESS_MAPPING_DEFAULT));
        result.put(HiveObjectType.TABLE, buildAccessMapping(config, HiveObjectType.TABLE,
                TABLE_ACCESS_MAPPING_DEFAULT));
        result.put(HiveObjectType.COLUMN, buildAccessMapping(config, HiveObjectType.COLUMN,
                COLUMN_ACCESS_MAPPING_DEFAULT));
        return result;
    }

    private AccessMappings buildAccessMapping(RangerPluginConfig config,
            HiveObjectType objectType,
            Map<RangerTrinoAccessType, List<HiveAccessType>> defaultMapping)
    {
        Map<String, List<HiveAccessType>> mapped = new HashMap<>();
        for (Map.Entry<RangerTrinoAccessType, List<HiveAccessType>> entry : defaultMapping.entrySet()) {
            String trinoAccessTypeValue = entry.getKey().name().toLowerCase(ENGLISH);
            List<HiveAccessType> hiveTypes = getAccessMappings(
                    config,
                    objectType,
                    trinoAccessTypeValue,
                    entry.getValue().stream()
                            .map(v -> v.name().toLowerCase(ENGLISH))
                            .distinct()
                            .toArray(String[]::new)
            );
            mapped.put(trinoAccessTypeValue, hiveTypes);
        }
        return new AccessMappings(mapped);
    }

    private List<HiveAccessType> getAccessMappings(RangerPluginConfig config,
            HiveObjectType objectType,
            String accessType,
            String... defaultMappings)
    {
        String configKey = config.getPropertyPrefix() +
                String.format(ACCESS_MAPPINGS_KEY_TEMPLATE,
                        objectType.toString().toLowerCase(), accessType);
        String[] mappings = config.getStrings(configKey, defaultMappings);
        return getAccessMappings(mappings);
    }
}
