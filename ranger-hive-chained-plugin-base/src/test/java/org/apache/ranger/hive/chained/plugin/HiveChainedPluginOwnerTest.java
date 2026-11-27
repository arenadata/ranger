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

import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_FILE_LOCATION_POSTFIX;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.RESOURCE_OWNER_ENABLED;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.TABLE_COLUMN_SCOPED_READ_ENABLED_POSTFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.hive.chained.mapping.HiveEntity;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {OWNER} in Hive policies applies to the requests of an RMM-based chained plugin: the owner of the mapped Hive table
 * or database comes with the mapping. The mappings are read from the local mappings file, as after a restart.
 */
public class HiveChainedPluginOwnerTest {
    private static final String HIVE_SERVICE = "dev_hive";
    private static final long OWNER_POLICY_ID = 7;
    private static final String READ = "read";

    private static final String TABLE_FILE = "/warehouse/db1.db/t1/part=1/000000_0";
    private static final String DATABASE_DIR = "/warehouse/db1.db/tmp";
    private static final String TABLE_WITHOUT_OWNER_FILE = "/warehouse/db1.db/t2/000000_0";

    @TempDir
    private Path tempDir;

    @Test
    public void testTableOwnerIsAllowedByOwnerPolicy() throws Exception {
        TestPlugin plugin = newPlugin(true, false);

        RangerAccessResult result = plugin.isAccessAllowed(request(TABLE_FILE, "alice"));

        assertTrue(result.getIsAllowed());
        assertEquals(OWNER_POLICY_ID, result.getPolicyId());
        assertEquals(HIVE_SERVICE, result.getServiceName());
    }

    @Test
    public void testOtherUserIsNotAllowedByOwnerPolicy() throws Exception {
        TestPlugin plugin = newPlugin(true, false);

        assertFalse(plugin.isAccessAllowed(request(TABLE_FILE, "bob")).getIsAllowed());
    }

    @Test
    public void testOwnerIsIgnoredByDefault() throws Exception {
        TestPlugin plugin = newPlugin(null, false);

        assertFalse(plugin.isAccessAllowed(request(TABLE_FILE, "alice")).getIsAllowed());
    }

    /** the option is a config of the Hive service, it is switched with the next policy download */
    @Test
    public void testOwnerIsSwitchedOffWithTheHivePolicies() throws Exception {
        TestPlugin plugin = newPlugin(true, false);
        assertTrue(plugin.isAccessAllowed(request(TABLE_FILE, "alice")).getIsAllowed());

        plugin.setHivePolicies(false, ownerPolicy("*"));

        assertFalse(plugin.isAccessAllowed(request(TABLE_FILE, "alice")).getIsAllowed());
    }

    /** a mapping published before RMM started to publish owners */
    @Test
    public void testMappingWithoutOwner() throws Exception {
        TestPlugin plugin = newPlugin(true, false);

        assertFalse(plugin.isAccessAllowed(request(TABLE_WITHOUT_OWNER_FILE, "alice")).getIsAllowed());
    }

    @Test
    public void testDatabaseOwnerOwnsTheDatabase() throws Exception {
        TestPlugin plugin = newPlugin(true, false);

        assertTrue(plugin.isAccessAllowed(request(DATABASE_DIR, "dbowner")).getIsAllowed());
        assertFalse(plugin.isAccessAllowed(request(DATABASE_DIR, "alice")).getIsAllowed());
    }

    /** a plugin that maps a table of the request through the database mapping, as the StarRocks one does */
    @Test
    public void testDatabaseOwnerDoesNotOwnItsTables() throws Exception {
        TestPlugin plugin = newPlugin(true, true);

        RangerAccessRequestImpl request = request(DATABASE_DIR, "dbowner");
        ((RangerAccessResourceImpl) request.getResource()).setValue("table", "v1");

        assertFalse(plugin.isAccessAllowed(request).getIsAllowed());
    }

    @Test
    public void testTableOwnerOwnsItsColumns() throws Exception {
        TestPlugin plugin = newPlugin(true, true);

        RangerAccessRequestImpl request = request(TABLE_FILE, "alice");
        ((RangerAccessResourceImpl) request.getResource()).setValue("column", "c1");

        assertTrue(plugin.isAccessAllowed(request).getIsAllowed());
    }

    /** a table read allowed by an {OWNER} policy on one of its columns, with the column scoped read enabled */
    @Test
    public void testColumnOwnerPolicyAllowsTableReadOfTheOwner() throws Exception {
        TestPlugin plugin = newPlugin(true, false, true, ownerPolicy("c1"));

        assertTrue(plugin.isAccessAllowed(request(TABLE_FILE, "alice")).getIsAllowed());
        assertFalse(plugin.isAccessAllowed(request(TABLE_FILE, "bob")).getIsAllowed());
    }

    private TestPlugin newPlugin(Boolean resourceOwnerEnabled, boolean mapsRequestLevels) throws Exception {
        return newPlugin(resourceOwnerEnabled, mapsRequestLevels, false, ownerPolicy("*"));
    }

    private TestPlugin newPlugin(Boolean resourceOwnerEnabled, boolean mapsRequestLevels, boolean columnScopedRead,
                                 RangerPolicy policy) throws Exception {
        Path mappingsFile = tempDir.resolve("mappings");
        Files.write(mappingsFile, Arrays.asList(
            "1",
            "{\"path\":\"/warehouse/db1.db\",\"name\":\"hive.db1\",\"type\":\"DATABASE\",\"owner\":\"dbowner\"}",
            "{\"path\":\"/warehouse/db1.db/t1\",\"name\":\"hive.db1.t1\",\"type\":\"TABLE\",\"owner\":\"alice\"}",
            "{\"path\":\"/warehouse/db1.db/t2\",\"name\":\"hive.db1.t2\",\"type\":\"TABLE\"}"),
            StandardCharsets.UTF_8);

        RangerPluginConfig config = new RangerPluginConfig("test", "test", "test", null, null, null);
        config.set(config.getPropertyPrefix() + MAPPINGS_FILE_LOCATION_POSTFIX, mappingsFile.toString());
        config.setBoolean(config.getPropertyPrefix() + TABLE_COLUMN_SCOPED_READ_ENABLED_POSTFIX, columnScopedRead);

        RangerPluginContext pluginContext = mock(RangerPluginContext.class);
        when(pluginContext.getConfig()).thenReturn(config);
        when(pluginContext.getAdminClient()).thenReturn(mock(RangerAdminClient.class));

        RangerBasePlugin rootPlugin = mock(RangerBasePlugin.class);
        when(rootPlugin.getConfig()).thenReturn(config);
        when(rootPlugin.getPluginContext()).thenReturn(pluginContext);
        when(rootPlugin.getServiceType()).thenReturn("test");
        when(rootPlugin.getAppId()).thenReturn("test");

        TestPlugin plugin = new TestPlugin(rootPlugin, mapsRequestLevels, columnScopedRead);
        plugin.setHivePolicies(resourceOwnerEnabled, policy);
        return plugin;
    }

    private static RangerPolicy ownerPolicy(String column) {
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource("db1"));
        resources.put("table", new RangerPolicyResource("*"));
        resources.put("column", new RangerPolicyResource(column));

        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(Collections.singletonList(RangerPolicyEngine.RESOURCE_OWNER));
        item.setAccesses(Collections.singletonList(new RangerPolicyItemAccess("select", true)));

        RangerPolicy policy = new RangerPolicy();
        policy.setId(OWNER_POLICY_ID);
        policy.setName("owner");
        policy.setService(HIVE_SERVICE);
        policy.setIsEnabled(true);
        policy.setResources(resources);
        policy.setPolicyItems(Collections.singletonList(item));
        return policy;
    }

    private static RangerAccessRequestImpl request(String path, String user) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("path", path);
        return new RangerAccessRequestImpl(resource, READ, user, Collections.emptySet(), Collections.emptySet());
    }

    private static class TestPlugin extends HiveChainedPlugin {
        private static final Map<HiveObjectType, AccessMappings> ACCESS_TYPE_MAPPINGS = new HashMap<>();

        static {
            ACCESS_TYPE_MAPPINGS.put(HiveObjectType.DATABASE,
                new AccessMappings(Collections.singletonMap(READ, Collections.singletonList(HiveAccessType.USE))));
            ACCESS_TYPE_MAPPINGS.put(HiveObjectType.TABLE,
                new AccessMappings(Collections.singletonMap(READ, Collections.singletonList(HiveAccessType.SELECT))));
            ACCESS_TYPE_MAPPINGS.put(HiveObjectType.COLUMN,
                new AccessMappings(Collections.singletonMap(READ, Collections.singletonList(HiveAccessType.SELECT))));
        }

        private final boolean mapsRequestLevels;
        private final boolean columnScopedRead;

        public TestPlugin(RangerBasePlugin rootPlugin, boolean mapsRequestLevels, boolean columnScopedRead) throws IOException {
            super(rootPlugin, HIVE_SERVICE);
            this.mapsRequestLevels = mapsRequestLevels;
            this.columnScopedRead = columnScopedRead;
        }

        @Override
        protected boolean isColumnScopedReadSupported() {
            return columnScopedRead;
        }

        public void setHivePolicies(Boolean resourceOwnerEnabled, RangerPolicy... policies) throws Exception {
            ServicePolicies servicePolicies = new ServicePolicies();
            if (resourceOwnerEnabled != null) {
                servicePolicies.setServiceConfig(
                    Collections.singletonMap(RESOURCE_OWNER_ENABLED, resourceOwnerEnabled.toString()));
            }
            servicePolicies.setServiceName(HIVE_SERVICE);
            servicePolicies.setPolicyVersion(1L);
            servicePolicies.setServiceDef(EmbeddedServiceDefsUtil.instance()
                .getEmbeddedServiceDef(EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_HIVE_NAME));
            servicePolicies.setPolicies(Arrays.asList(policies));
            plugin.setPolicies(servicePolicies);
        }

        @Override
        protected Optional<String> getPathFromRequest(RangerAccessRequest request) {
            return Optional.ofNullable((String) request.getResource().getValue("path"));
        }

        @Override
        protected Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType) {
            return Optional.ofNullable(ACCESS_TYPE_MAPPINGS.get(hiveObjectType));
        }

        /** with mapsRequestLevels, the table and column of the request below the mapped entity are carried over */
        @Override
        protected RangerHiveResource toHiveResource(HiveEntity entity, RangerAccessRequest request) {
            RangerHiveResource mapped = toHiveResource(entity);
            if (!mapsRequestLevels) {
                return mapped;
            }
            String table = (String) request.getResource().getValue("table");
            String column = (String) request.getResource().getValue("column");
            List<String> names = entity.getNameSegments();
            if (entity.getType() == HiveObjectType.DATABASE && table != null) {
                return new RangerHiveResource(HiveObjectType.TABLE, names.get(1), table);
            }
            if (entity.getType() == HiveObjectType.TABLE && column != null) {
                return new RangerHiveResource(HiveObjectType.COLUMN, names.get(1), names.get(2), column);
            }
            return mapped;
        }
    }
}
