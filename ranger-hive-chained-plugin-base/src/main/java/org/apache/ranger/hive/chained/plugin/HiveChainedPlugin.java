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

package org.apache.ranger.hive.chained.plugin;

import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_FILE_LOCATION_DEFAULT;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_FILE_LOCATION_POSTFIX;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_PERSIST_INTERVAL_DEFAULT;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_PERSIST_INTERVAL_POSTFIX;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_REFRESH_INTERVAL_DEFAULT;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_REFRESH_INTERVAL_POSTFIX;
import static org.apache.ranger.plugin.policyengine.RangerPolicyEngine.ANY_ACCESS;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveAccessRequest;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.hive.chained.mapping.HiveMappingFetcher;
import org.apache.ranger.hive.chained.mapping.HiveResourceMappingStore;
import org.apache.ranger.hive.chained.mapping.TrieHiveResourceMappingStore;
import org.apache.ranger.plugin.mapping.ResourceMappingFetcher;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerResourceACLs;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.service.ResourceMappingChainedPlugin;

@Slf4j
public abstract class HiveChainedPlugin extends ResourceMappingChainedPlugin {
    public static final String HIVE_SERVICE_TYPE = "hive";

    private final HiveResourceMappingStore resourceMappingsStore;
    private final ResourceMappingFetcher resourceMappingFetcher;

    protected HiveChainedPlugin(RangerBasePlugin rootPlugin, String serviceName) throws IOException {
        super(rootPlugin, HIVE_SERVICE_TYPE, serviceName);

        String mappingsFileLocation = rootPlugin.getConfig().get(
            rootPlugin.getConfig().getPropertyPrefix()
                + MAPPINGS_FILE_LOCATION_POSTFIX, MAPPINGS_FILE_LOCATION_DEFAULT);
        this.resourceMappingsStore = new TrieHiveResourceMappingStore(mappingsFileLocation);

        long refreshInterval = rootPlugin.getConfig().getLong(
            rootPlugin.getConfig().getPropertyPrefix()
                + MAPPINGS_REFRESH_INTERVAL_POSTFIX, MAPPINGS_REFRESH_INTERVAL_DEFAULT);
        long mappingsPersistInterval = rootPlugin.getConfig().getLong(
            rootPlugin.getConfig().getPropertyPrefix()
                + MAPPINGS_PERSIST_INTERVAL_POSTFIX, MAPPINGS_PERSIST_INTERVAL_DEFAULT);

        RangerPluginContext pluginContext = rootPlugin.getPluginContext();
        RangerAdminClient adminClient = Optional.ofNullable(pluginContext.getAdminClient())
            .orElseGet(() -> pluginContext.createAdminClient(rootPlugin.getConfig()));

        this.resourceMappingFetcher = newMappingFetcher(
            adminClient,
            resourceMappingsStore,
            refreshInterval,
            mappingsPersistInterval,
            rootPlugin.getServiceType()
        );
    }

    @Override
    public void init() {
        super.init();

        resourceMappingFetcher.start();
    }

    @Override
    public RangerResourceACLs getResourceACLs(RangerAccessRequest request, Integer policyType) {
        // currently we don't need to override the root plugins ACLs
        return null;
    }

    @Override
    public RangerResourceACLs getResourceACLs(RangerAccessRequest request) {
        // currently we don't need to override the root plugins ACLs
        return null;
    }

    @Override
    protected List<RangerAccessRequest> toChainedRequests(RangerAccessRequest request) {
        Optional<String> maybePath = Optional.ofNullable(request)
            .map(this::getPathFromRequest)
            .filter(Optional::isPresent)
            .map(Optional::get);

        if (!maybePath.isPresent()) {
            log.warn("Error extracting path from request {}", request);
            return Collections.emptyList();
        }

        Optional<HiveEntity> maybeHiveEntity = maybePath.flatMap(resourceMappingsStore::get);
        if (!maybeHiveEntity.isPresent()) {
            log.debug("No Hive resource mapping found for path {}", maybePath.get());
            return Collections.emptyList();
        }

        return maybeHiveEntity
            .map(entity -> toAccessRequests(entity, request))
            .orElseGet(Collections::emptyList);
    }

    protected List<HiveAccessType> getAccessMappings(String... mappings) {
        return Arrays.stream(mappings)
            .map(this::toAccessType)
            .collect(Collectors.toList());
    }

    protected HiveAccessType toAccessType(String rawAccessType) {
        return rawAccessType.equals(ANY_ACCESS)
            // Hive plugin uses "USE" access type as synonym for "_any"
            ? HiveAccessType.USE
            : HiveAccessType.valueOf(rawAccessType);
    }

    protected abstract Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType);

    protected abstract Optional<String> getPathFromRequest(RangerAccessRequest request);

    protected List<HiveAccessType> getHiveAccessTypes(HiveEntity entity, RangerAccessRequest request) {
        List<HiveAccessType> accessTypes = getAccessTypeMappings(entity.getType())
            .map(mappings -> mappings.getHiveAccessTypes(request))
            .orElseGet(Collections::emptyList);

        if (accessTypes.isEmpty()) {
            log.warn("No access type mapping found for request: {}", request);
        }
        return accessTypes;
    }

    protected HiveMappingFetcher newMappingFetcher(
        RangerAdminClient adminClient,
        HiveResourceMappingStore mappingStore,
        long refreshInterval,
        long mappingsPersistInterval,
        String targetService) {
        return new HiveMappingFetcher(
            adminClient,
            mappingStore,
            refreshInterval,
            mappingsPersistInterval,
            targetService
        );
    }

    private List<RangerAccessRequest> toAccessRequests(HiveEntity entity, RangerAccessRequest srcRequest) {
        return getHiveAccessTypes(entity, srcRequest)
            .stream()
            .map(accessType -> new RangerHiveAccessRequest(
                toHiveResource(entity),
                srcRequest.getUser(),
                srcRequest.getUserGroups(),
                srcRequest.getUserRoles(),
                entity.toString(),
                accessType,
                null,
                null
            ))
            .collect(Collectors.toList());
    }

    private RangerHiveResource toHiveResource(HiveEntity entity) {
        List<String> nameSegments = entity.getNameSegments();

        if (entity.getType() == HiveObjectType.DATABASE) {
            if (nameSegments.size() < 2) {
                throw new IllegalArgumentException("Wrong db name: " + entity.fullName());
            }
            return new RangerHiveResource(entity.getType(), nameSegments.get(1));
        }

        if (nameSegments.size() < 3) {
            throw new IllegalArgumentException("Wrong entity name: " + entity.fullName());
        }
        return new RangerHiveResource(
            entity.getType(),
            nameSegments.get(1),
            nameSegments.get(2)
        );
    }

    protected static class AccessMappings {
        private final Map<String, List<HiveAccessType>> accessTypeMappings;

        public AccessMappings(Map<String, List<HiveAccessType>> accessTypeMappings) {
            this.accessTypeMappings = accessTypeMappings;
        }

        public List<HiveAccessType> getHiveAccessTypes(RangerAccessRequest request) {
            log.debug("Trying to get access type mappings for {}", request);
            return accessTypeMappings.getOrDefault(request.getAccessType(), Collections.emptyList());
        }
    }
}
