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

import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveAccessRequest;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.service.ResourceMappingChainedPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.ranger.plugin.policyengine.RangerPolicyEngine.ANY_ACCESS;

@Slf4j
public abstract class BaseHiveChainedPlugin extends ResourceMappingChainedPlugin
{
    protected BaseHiveChainedPlugin(RangerBasePlugin rootPlugin, String serviceType,
            String serviceName)
    {
        super(rootPlugin, serviceType, serviceName);
    }

    protected List<RangerAccessRequest> toHiveAccessRequests(RangerHiveResource hiveResource,
            RangerAccessRequest srcRequest)
    {
        return getHiveAccessTypes(hiveResource, srcRequest)
                .stream()
                .map(accessType -> new RangerHiveAccessRequest(
                        hiveResource,
                        srcRequest.getUser(),
                        srcRequest.getUserGroups(),
                        srcRequest.getUserRoles(),
                        getActionDescription(srcRequest, accessType),
                        accessType,
                        null,
                        null
                ))
                .collect(Collectors.toList());
    }

    protected String getActionDescription(RangerAccessRequest srcRequest, HiveAccessType hiveAccessType)
    {
        return String.format("%s (%s) -> %s",
                srcRequest.getAccessType(),
                Optional.ofNullable(srcRequest.getResource())
                        .map(RangerAccessResource::getAsString)
                        .orElse(""),
                hiveAccessType);
    }

    protected List<HiveAccessType> getHiveAccessTypes(RangerHiveResource hiveResource, RangerAccessRequest request)
    {
        List<HiveAccessType> accessTypes = getAccessTypeMappings(hiveResource.getObjectType())
                .map(mappings -> mappings.getHiveAccessTypes(request))
                .orElseGet(Collections::emptyList);

        if (accessTypes.isEmpty()) {
            log.warn("No access type mapping found for request: {}", request);
        }
        return accessTypes;
    }

    protected List<HiveAccessType> getAccessMappings(String... mappings) {
        return Arrays.stream(mappings)
                .map(this::toAccessType)
                .collect(Collectors.toList());
    }

    protected HiveAccessType toAccessType(String rawAccessType) {
        String accessType = rawAccessType.trim();
        return accessType.equals(ANY_ACCESS)
                // Hive plugin uses "USE" access type as synonym for "_any"
                ? HiveAccessType.USE
                : HiveAccessType.valueOf(accessType.toUpperCase());
    }

    protected abstract Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType);

    protected static class AccessMappings
    {
        private final Map<String, List<HiveAccessType>> accessTypeMappings;

        public AccessMappings(Map<String, List<HiveAccessType>> accessTypeMappings)
        {
            this.accessTypeMappings = accessTypeMappings;
        }

        public List<HiveAccessType> getHiveAccessTypes(RangerAccessRequest request)
        {
            log.debug("Trying to get access type mappings for {}", request);
            return accessTypeMappings.getOrDefault(request.getAccessType(), Collections.emptyList());
        }
    }
}
