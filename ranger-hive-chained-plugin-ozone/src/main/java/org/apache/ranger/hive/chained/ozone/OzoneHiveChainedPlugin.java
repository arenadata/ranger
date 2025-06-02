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

package org.apache.ranger.hive.chained.ozone;

import static org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer.KEY_RESOURCE_BUCKET;
import static org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer.KEY_RESOURCE_KEY;
import static org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer.KEY_RESOURCE_VOLUME;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.ACCESS_MAPPINGS_KEY_TEMPLATE;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_CREATE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_DELETE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_LIST_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_READ_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_READ_ACL_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_WRITE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.DB_WRITE_ACL_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_CREATE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_DELETE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_LIST_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_READ_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_READ_ACL_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_WRITE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.ozone.ConfigKeys.TABLE_WRITE_ACL_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.plugin.policyengine.RangerAccessResource.RESOURCE_SEP;
import static org.apache.ranger.plugin.policyengine.RangerPolicyEngine.ANY_ACCESS;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_CREATE;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_DELETE;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_LIST;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_READ;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_READ_ACL;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_WRITE;
import static org.apache.ranger.services.ozone.RangerServiceOzone.ACCESS_TYPE_WRITE_ACL;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableMap;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.hive.chained.mapping.HiveMappingFetcher;
import org.apache.ranger.hive.chained.mapping.HiveResourceMappingStore;
import org.apache.ranger.hive.chained.plugin.HiveChainedPlugin;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.service.RangerBasePlugin;

@Slf4j
public class OzoneHiveChainedPlugin extends HiveChainedPlugin {
    private final Map<HiveObjectType, AccessMappings> accessTypeMappings;

    public OzoneHiveChainedPlugin(RangerBasePlugin rootPlugin, String serviceName) throws IOException {
        super(rootPlugin, serviceName);

        this.accessTypeMappings = buildAccessTypeMappings(rootPlugin.getConfig());
    }

    @Override
    protected Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType) {
        return Optional.ofNullable(accessTypeMappings.get(hiveObjectType));
    }

    @Override
    protected Optional<String> getPathFromRequest(RangerAccessRequest request) {
        RangerAccessResource ozoneResource = request.getResource();
        String volume = (String) ozoneResource.getValue(KEY_RESOURCE_VOLUME);
        if (volume == null) {
            log.warn("Wrong Ozone access request {}. Volume can't be null.", request);
            return Optional.empty();
        }

        StringJoiner stringJoiner = new StringJoiner(RESOURCE_SEP, RESOURCE_SEP, "");
        stringJoiner.add(volume);

        Optional.ofNullable(ozoneResource.getValue(KEY_RESOURCE_BUCKET))
            .map(String.class::cast)
            .ifPresent(stringJoiner::add);

        Optional.ofNullable(ozoneResource.getValue(KEY_RESOURCE_KEY))
            .map(String.class::cast)
            .ifPresent(stringJoiner::add);

        return Optional.of(stringJoiner.toString());
    }

    @Override
    protected HiveMappingFetcher newMappingFetcher(RangerAdminClient adminClient,
                                                   HiveResourceMappingStore mappingStore,
                                                   long refreshInterval, long mappingsPersistInterval,
                                                   String targetService) {
        return new OzoneHiveMappingFetcher(
            adminClient, mappingStore, refreshInterval, mappingsPersistInterval, targetService);
    }

    private Map<HiveObjectType, AccessMappings> buildAccessTypeMappings(RangerPluginConfig config) {
        return ImmutableMap.of(
            HiveObjectType.TABLE, buildTableAccessTypeMappings(config),
            HiveObjectType.DATABASE, buildDbAccessTypeMappings(config)
        );
    }

    private AccessMappings buildTableAccessTypeMappings(RangerPluginConfig config) {
        Map<String, List<HiveAccessType>> mappings = new HashMap<>();
        mappings.put(ANY_ACCESS, Collections.singletonList(HiveAccessType.USE));
        mappings.put(
            ACCESS_TYPE_READ,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_READ, TABLE_READ_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_WRITE,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_WRITE, TABLE_WRITE_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_CREATE,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_CREATE, TABLE_CREATE_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_LIST,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_LIST, TABLE_LIST_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_DELETE,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_DELETE, TABLE_DELETE_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_READ_ACL,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_READ_ACL,
                TABLE_READ_ACL_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_WRITE_ACL,
            getAccessMappings(config, HiveObjectType.TABLE, ACCESS_TYPE_WRITE_ACL,
                TABLE_WRITE_ACL_ACCESS_MAPPINGS_DEFAULT)
        );

        log.debug("Hive DB access type mappings : {}", mappings);
        return new AccessMappings(mappings);
    }

    private AccessMappings buildDbAccessTypeMappings(RangerPluginConfig config) {
        Map<String, List<HiveAccessType>> mappings = new HashMap<>();

        mappings.put(ANY_ACCESS, Collections.singletonList(HiveAccessType.USE));
        mappings.put(
            ACCESS_TYPE_READ,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_READ, DB_READ_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_WRITE,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_WRITE, DB_WRITE_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_CREATE,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_CREATE, DB_CREATE_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_LIST,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_LIST, DB_LIST_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_DELETE,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_DELETE, DB_DELETE_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_READ_ACL,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_READ_ACL,
                DB_READ_ACL_ACCESS_MAPPINGS_DEFAULT)
        );
        mappings.put(
            ACCESS_TYPE_WRITE_ACL,
            getAccessMappings(config, HiveObjectType.DATABASE, ACCESS_TYPE_WRITE_ACL,
                DB_WRITE_ACL_ACCESS_MAPPINGS_DEFAULT)
        );

        log.debug("Hive table access type mappings : {}", mappings);
        return new AccessMappings(mappings);
    }

    private List<HiveAccessType> getAccessMappings(RangerPluginConfig config,
                                                   HiveObjectType objectType,
                                                   String accessType,
                                                   String... defaultMappings) {
        String configKey = String.format(ACCESS_MAPPINGS_KEY_TEMPLATE,
            objectType.toString().toLowerCase(),
            accessType);
        String[] mappings = config.getStrings(configKey, defaultMappings);
        return getAccessMappings(mappings);
    }
}
