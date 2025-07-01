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

package org.apache.ranger.hive.chained.hdfs;

import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.ACCESS_MAPPINGS_KEY_TEMPLATE;
import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.DB_EXECUTE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.DB_READ_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.DB_WRITE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.TABLE_EXECUTE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.TABLE_READ_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.hive.chained.hdfs.ConfigKeys.TABLE_WRITE_ACCESS_MAPPINGS_DEFAULT;
import static org.apache.ranger.plugin.policyengine.RangerPolicyEngine.ANY_ACCESS;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableMap;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hadoop.RangerHdfsAuthorizer;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.hive.chained.mapping.HiveMappingFetcher;
import org.apache.ranger.hive.chained.mapping.HiveResourceMappingStore;
import org.apache.ranger.hive.chained.plugin.HiveChainedPlugin;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.service.RangerBasePlugin;

@Slf4j
public class HdfsHiveChainedPlugin extends HiveChainedPlugin {
    public static final String READ_ACCESS_TYPE = "read";
    public static final String WRITE_ACCESS_TYPE = "write";
    public static final String EXECUTE_ACCESS_TYPE = "execute";

    private final Map<HiveObjectType, AccessMappings> accessTypeMappings;

    public HdfsHiveChainedPlugin(RangerBasePlugin rootPlugin, String serviceName) throws IOException {
        super(rootPlugin, serviceName);

        this.accessTypeMappings = buildAccessTypeMappings(rootPlugin.getConfig());
    }

    @Override
    protected Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType) {
        return Optional.ofNullable(accessTypeMappings.get(hiveObjectType));
    }

    @Override
    protected Optional<String> getPathFromRequest(RangerAccessRequest request) {
        Optional<String> maybePath = Optional.ofNullable(request.getResource())
            .map(resource -> resource.getValue(RangerHdfsAuthorizer.KEY_RESOURCE_PATH))
            .map(Object::toString);

        if (!maybePath.isPresent()) {
            log.warn("Can't extract path from HDFS access request: {}", request);
        }
        return maybePath;
    }

    @Override
    protected HiveMappingFetcher newMappingFetcher(RangerAdminClient adminClient, HiveResourceMappingStore mappingStore,
                                                   long refreshInterval, long mappingsPersistInterval,
                                                   String targetService) {
        return new HdfsHiveMappingFetcher(
            adminClient,
            mappingStore,
            refreshInterval,
            mappingsPersistInterval,
            targetService);
    }

    private Map<HiveObjectType, AccessMappings> buildAccessTypeMappings(RangerPluginConfig config) {
        return ImmutableMap.of(
            HiveObjectType.TABLE, buildTableAccessTypeMappings(config),
            HiveObjectType.DATABASE, buildDbAccessTypeMappings(config)
        );
    }

    private AccessMappings buildTableAccessTypeMappings(RangerPluginConfig config) {
        Map<String, List<HiveAccessType>> mappings = ImmutableMap.of(
            ANY_ACCESS,
            Collections.singletonList(HiveAccessType.USE),
            READ_ACCESS_TYPE,
            getAccessMappings(config, HiveObjectType.TABLE, READ_ACCESS_TYPE, TABLE_READ_ACCESS_MAPPINGS_DEFAULT),
            WRITE_ACCESS_TYPE,
            getAccessMappings(config, HiveObjectType.TABLE, WRITE_ACCESS_TYPE, TABLE_WRITE_ACCESS_MAPPINGS_DEFAULT),
            EXECUTE_ACCESS_TYPE,
            getAccessMappings(config, HiveObjectType.TABLE, EXECUTE_ACCESS_TYPE, TABLE_EXECUTE_ACCESS_MAPPINGS_DEFAULT)
        );

        log.debug("Hive DB access type mappings : {}", mappings);
        return new AccessMappings(mappings);
    }

    private AccessMappings buildDbAccessTypeMappings(RangerPluginConfig config) {
        Map<String, List<HiveAccessType>> mappings = ImmutableMap.of(
            ANY_ACCESS,
            Collections.singletonList(HiveAccessType.USE),
            READ_ACCESS_TYPE,
            getAccessMappings(config, HiveObjectType.DATABASE, READ_ACCESS_TYPE, DB_READ_ACCESS_MAPPINGS_DEFAULT),
            WRITE_ACCESS_TYPE,
            getAccessMappings(config, HiveObjectType.DATABASE, WRITE_ACCESS_TYPE, DB_WRITE_ACCESS_MAPPINGS_DEFAULT),
            EXECUTE_ACCESS_TYPE,
            getAccessMappings(config, HiveObjectType.DATABASE, EXECUTE_ACCESS_TYPE, DB_EXECUTE_ACCESS_MAPPINGS_DEFAULT)
        );

        log.debug("Hive table access type mappings : {}", mappings);
        return new AccessMappings(mappings);
    }

    private List<HiveAccessType> getAccessMappings(RangerPluginConfig config,
                                                   HiveObjectType objectType,
                                                   String accessType,
                                                   String... defaultMappings) {
        String configKey = config.getPropertyPrefix() +
            String.format(ACCESS_MAPPINGS_KEY_TEMPLATE,
                objectType.toString().toLowerCase(), accessType);
        String[] mappings = config.getStrings(configKey, defaultMappings);
        return getAccessMappings(mappings);
    }
}
