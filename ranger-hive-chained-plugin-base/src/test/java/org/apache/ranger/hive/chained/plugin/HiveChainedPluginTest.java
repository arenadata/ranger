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
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.TABLE_COLUMN_SCOPED_READ_ENABLED_POSTFIX;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.hive.authorizer.HiveAccessType;
import org.apache.ranger.authorization.hive.authorizer.HiveObjectType;
import org.apache.ranger.authorization.hive.authorizer.RangerHiveResource;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineImpl;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HiveChainedPluginTest {
    private static final String HIVE_SERVICE = "hive";
    private static final String USER = "some_user";
    private static final String DB = "column_filter";
    private static final String TABLE = "test_table";

    private static final String READ_ACCESS_TYPE = "read";
    private static final String LIST_ACCESS_TYPE = "list";
    private static final String WRITE_ACCESS_TYPE = "write";

    private static final RangerHiveResource DB_RESOURCE = new RangerHiveResource(HiveObjectType.DATABASE, DB);
    private static final RangerHiveResource TABLE_RESOURCE = new RangerHiveResource(HiveObjectType.TABLE, DB, TABLE);

    @TempDir
    Path tempDir;

    private long nextPolicyId = 1;

    @Test
    void testColumnSelectPolicyDoesNotAllowTableReadByDefault() throws Exception {
        TestPlugin plugin = newPlugin(null);
        RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "id", "select"));

        assertFalse(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
    }

    @Test
    void testColumnSelectPolicyAllowsTableRead() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "id", "select"));

        assertTrue(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
    }

    @Test
    void testColumnSelectPolicyDoesNotAllowTableReadIfColumnScopedReadIsNotSupported() throws Exception {
        TestPlugin plugin = newPlugin(true, false);
        RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "id", "select"));

        assertFalse(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
    }

    @Test
    void testColumnSelectPolicyAllowsAnyAccessTypeMappedToSelect() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "id", "select"));

        assertTrue(isAllowed(engine, plugin, TABLE_RESOURCE, LIST_ACCESS_TYPE));
    }

    @Test
    void testAllColumnsSelectPolicyAllowsTableRead() throws Exception {
        for (Boolean columnScopedReadEnabled : Arrays.asList(null, true)) {
            TestPlugin plugin = newPlugin(columnScopedReadEnabled);
            RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "*", "select"));

            assertTrue(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
        }
    }

    @Test
    void testColumnSelectDenyPolicyDoesNotDenyTableRead() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(
            allowPolicy(TABLE, "id", "select"),
            denyPolicy(TABLE, "data", "select"));

        assertTrue(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
    }

    @Test
    void testColumnSelectDenyPolicyDoesNotDenyTableReadAllowedForAllColumns() throws Exception {
        for (Boolean columnScopedReadEnabled : Arrays.asList(null, true)) {
            TestPlugin plugin = newPlugin(columnScopedReadEnabled);
            RangerPolicyEngine engine = newEngine(
                allowPolicy(TABLE, "*", "select"),
                denyPolicy(TABLE, "data", "select"));

            assertTrue(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
        }
    }

    @Test
    void testTableSelectDenyPolicyDeniesTableRead() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(
            allowPolicy(TABLE, "id", "select"),
            denyPolicy(TABLE, "*", "select"));

        assertFalse(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
    }

    @Test
    void testColumnSelectPolicyOnAnotherTableDoesNotAllowTableRead() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(allowPolicy("another_table", "id", "select"));

        assertFalse(isAllowed(engine, plugin, TABLE_RESOURCE, READ_ACCESS_TYPE));
    }

    @Test
    void testColumnUpdatePolicyDoesNotAllowTableWrite() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "id", "select", "update", "alter"));

        assertFalse(isAllowed(engine, plugin, TABLE_RESOURCE, WRITE_ACCESS_TYPE));
    }

    @Test
    void testTablePolicyDoesNotAllowDbWrite() throws Exception {
        TestPlugin plugin = newPlugin(true);
        RangerPolicyEngine engine = newEngine(allowPolicy(TABLE, "*", "select", "create", "drop", "alter"));

        assertFalse(isAllowed(engine, plugin, DB_RESOURCE, WRITE_ACCESS_TYPE));
    }

    private TestPlugin newPlugin(Boolean tableColumnScopedReadEnabled) throws Exception {
        return newPlugin(tableColumnScopedReadEnabled, true);
    }

    private TestPlugin newPlugin(Boolean tableColumnScopedReadEnabled,
                                 boolean columnScopedReadSupported) throws Exception {
        RangerPluginConfig config = new RangerPluginConfig("test", "test", "test", null, null, null);
        config.set(config.getPropertyPrefix() + MAPPINGS_FILE_LOCATION_POSTFIX,
            tempDir.resolve("mappings").toString());
        if (tableColumnScopedReadEnabled != null) {
            config.setBoolean(config.getPropertyPrefix() + TABLE_COLUMN_SCOPED_READ_ENABLED_POSTFIX,
                tableColumnScopedReadEnabled);
        }

        RangerPluginContext pluginContext = mock(RangerPluginContext.class);
        when(pluginContext.getConfig()).thenReturn(config);
        when(pluginContext.getAdminClient()).thenReturn(mock(RangerAdminClient.class));

        RangerBasePlugin rootPlugin = mock(RangerBasePlugin.class);
        when(rootPlugin.getConfig()).thenReturn(config);
        when(rootPlugin.getPluginContext()).thenReturn(pluginContext);
        when(rootPlugin.getServiceType()).thenReturn("test");
        when(rootPlugin.getAppId()).thenReturn("test");

        return new TestPlugin(rootPlugin, columnScopedReadSupported);
    }

    private static RangerPolicyEngine newEngine(RangerPolicy... policies) throws Exception {
        ServicePolicies servicePolicies = new ServicePolicies();
        servicePolicies.setServiceName(HIVE_SERVICE);
        servicePolicies.setPolicyVersion(1L);
        servicePolicies.setServiceDef(EmbeddedServiceDefsUtil.instance().getEmbeddedServiceDef(HIVE_SERVICE));
        servicePolicies.setPolicies(Arrays.asList(policies));

        RangerPluginContext pluginContext = new RangerPluginContext(
            new RangerPluginConfig(HIVE_SERVICE, HIVE_SERVICE, HIVE_SERVICE, null, null, null));
        return new RangerPolicyEngineImpl(servicePolicies, pluginContext, null);
    }

    private static boolean isAllowed(RangerPolicyEngine engine, TestPlugin plugin,
                                     RangerHiveResource hiveResource, String accessType) {
        RangerAccessRequestImpl request = new RangerAccessRequestImpl();
        request.setUser(USER);
        request.setUserGroups(Collections.emptySet());
        request.setAccessType(accessType);

        List<RangerAccessRequest> hiveRequests = plugin.toHiveAccessRequests(hiveResource, request);
        assertFalse(hiveRequests.isEmpty());

        return hiveRequests.stream()
            .map(hiveRequest -> engine.evaluatePolicies(hiveRequest, RangerPolicy.POLICY_TYPE_ACCESS, null))
            .allMatch(RangerAccessResult::getIsAllowed);
    }

    private RangerPolicy allowPolicy(String table, String column, String... accessTypes) {
        RangerPolicy policy = newPolicy(table, column);
        policy.setPolicyItems(Collections.singletonList(newPolicyItem(accessTypes)));
        return policy;
    }

    private RangerPolicy denyPolicy(String table, String column, String... accessTypes) {
        RangerPolicy policy = newPolicy(table, column);
        policy.setDenyPolicyItems(Collections.singletonList(newPolicyItem(accessTypes)));
        return policy;
    }

    private RangerPolicy newPolicy(String table, String column) {
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource(DB));
        resources.put("table", new RangerPolicyResource(table));
        resources.put("column", new RangerPolicyResource(column));

        long id = nextPolicyId++;
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setService(HIVE_SERVICE);
        policy.setIsEnabled(true);
        policy.setResources(resources);
        return policy;
    }

    private static RangerPolicyItem newPolicyItem(String... accessTypes) {
        RangerPolicyItem policyItem = new RangerPolicyItem();
        policyItem.setUsers(Collections.singletonList(USER));
        policyItem.setAccesses(Arrays.stream(accessTypes)
            .map(accessType -> new RangerPolicyItemAccess(accessType, true))
            .collect(Collectors.toList()));
        return policyItem;
    }

    private static class TestPlugin extends HiveChainedPlugin {
        private static final Map<HiveObjectType, AccessMappings> ACCESS_TYPE_MAPPINGS = new HashMap<>();

        static {
            Map<String, List<HiveAccessType>> tableMappings = new HashMap<>();
            tableMappings.put(READ_ACCESS_TYPE, Collections.singletonList(HiveAccessType.SELECT));
            tableMappings.put(LIST_ACCESS_TYPE, Collections.singletonList(HiveAccessType.SELECT));
            tableMappings.put(WRITE_ACCESS_TYPE, Arrays.asList(HiveAccessType.UPDATE, HiveAccessType.ALTER));
            ACCESS_TYPE_MAPPINGS.put(HiveObjectType.TABLE, new AccessMappings(tableMappings));

            Map<String, List<HiveAccessType>> dbMappings = new HashMap<>();
            dbMappings.put(WRITE_ACCESS_TYPE,
                Arrays.asList(HiveAccessType.CREATE, HiveAccessType.DROP, HiveAccessType.ALTER));
            ACCESS_TYPE_MAPPINGS.put(HiveObjectType.DATABASE, new AccessMappings(dbMappings));
        }

        private final boolean columnScopedReadSupported;

        TestPlugin(RangerBasePlugin rootPlugin, boolean columnScopedReadSupported) throws IOException {
            super(rootPlugin, HIVE_SERVICE);
            this.columnScopedReadSupported = columnScopedReadSupported;
        }

        @Override
        protected boolean isColumnScopedReadSupported() {
            return columnScopedReadSupported;
        }

        @Override
        protected Optional<AccessMappings> getAccessTypeMappings(HiveObjectType hiveObjectType) {
            return Optional.ofNullable(ACCESS_TYPE_MAPPINGS.get(hiveObjectType));
        }

        @Override
        protected Optional<String> getPathFromRequest(RangerAccessRequest request) {
            return Optional.empty();
        }
    }
}
