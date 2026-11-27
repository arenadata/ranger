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

import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.MAPPINGS_FILE_LOCATION_POSTFIX;
import static org.apache.ranger.hive.chained.plugin.HiveChainedPluginConfigKeys.RESOURCE_OWNER_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerDataMaskPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemDataMaskInfo;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceDef;
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
 * {OWNER} in Hive policies applies to StarRocks requests on Hive catalogs: RMM publishes the owner of the Hive table
 * or database with the {@code hive -> starrocks} mapping.
 */
public class StarRocksHiveChainedPluginOwnerTest {
    private static final String HIVE_SERVICE = "dev_hive";
    private static final long ACCESS_POLICY_ID = 11;
    private static final String CATALOG = "hive_catalog";

    @TempDir
    private Path tempDir;

    /** a Hive view RMM does not publish is mapped through its database: the database owner does not own it */
    @Test
    public void databaseOwnerOwnsTheDatabaseOnly() throws Exception {
        TestPlugin plugin = newPlugin();

        assertTrue(plugin.isAccessAllowed(request("dbowner", "usage", "db1")).getIsAllowed());
        assertFalse(plugin.isAccessAllowed(request("dbowner", "select", "db1", "v1")).getIsAllowed());
    }

    @Test
    public void ownerSeesTheColumnUnmasked() throws Exception {
        TestPlugin plugin = newPlugin();

        RangerAccessResult owner = plugin.evalDataMaskPolicies(request("alice", "select", "db1", "t1", "c1"));
        assertEquals(RangerPolicy.MASK_TYPE_NONE, owner.getMaskType());

        RangerAccessResult other = plugin.evalDataMaskPolicies(request("bob", "select", "db1", "t1", "c1"));
        assertEquals(RangerPolicy.MASK_TYPE_NULL, other.getMaskType());
    }

    private TestPlugin newPlugin() throws Exception {
        Path mappingsFile = tempDir.resolve("mappings");
        Files.write(mappingsFile, Arrays.asList(
            "1",
            "{\"path\":\"/hive_catalog/db1\",\"name\":\"hive.db1\",\"type\":\"DATABASE\",\"owner\":\"dbowner\"}",
            "{\"path\":\"/hive_catalog/db1/t1\",\"name\":\"hive.db1.t1\",\"type\":\"TABLE\",\"owner\":\"alice\"}"),
            StandardCharsets.UTF_8);

        RangerPluginConfig config = new RangerPluginConfig("starrocks", "dev_starrocks", "starrocks", null, null, null);
        config.set(config.getPropertyPrefix() + MAPPINGS_FILE_LOCATION_POSTFIX, mappingsFile.toString());

        RangerPluginContext pluginContext = mock(RangerPluginContext.class);
        when(pluginContext.getConfig()).thenReturn(config);
        when(pluginContext.getAdminClient()).thenReturn(mock(RangerAdminClient.class));

        RangerBasePlugin rootPlugin = mock(RangerBasePlugin.class);
        when(rootPlugin.getConfig()).thenReturn(config);
        when(rootPlugin.getPluginContext()).thenReturn(pluginContext);
        when(rootPlugin.getServiceType()).thenReturn("starrocks");
        when(rootPlugin.getAppId()).thenReturn("starrocks");
        when(rootPlugin.getServiceDef()).thenReturn(starRocksServiceDef());

        TestPlugin plugin = new TestPlugin(rootPlugin);
        plugin.setHivePolicies(accessPolicy(), maskPolicy());
        return plugin;
    }

    private static RangerPolicy accessPolicy() {
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(Collections.singletonList(RangerPolicyEngine.RESOURCE_OWNER));
        item.setAccesses(Collections.singletonList(new RangerPolicyItemAccess("select", true)));

        RangerPolicy policy = newPolicy(ACCESS_POLICY_ID, "*", "*");
        policy.setPolicyItems(Collections.singletonList(item));
        return policy;
    }

    private static RangerPolicy maskPolicy() {
        List<RangerPolicyItemAccess> select = Collections.singletonList(new RangerPolicyItemAccess("select", true));
        RangerDataMaskPolicyItem ownerItem = new RangerDataMaskPolicyItem(select,
            new RangerPolicyItemDataMaskInfo(RangerPolicy.MASK_TYPE_NONE, null, null),
            Collections.singletonList(RangerPolicyEngine.RESOURCE_OWNER), null, null, null, false);
        RangerDataMaskPolicyItem othersItem = new RangerDataMaskPolicyItem(select,
            new RangerPolicyItemDataMaskInfo(RangerPolicy.MASK_TYPE_NULL, null, null),
            null, Collections.singletonList(RangerPolicyEngine.GROUP_PUBLIC), null, null, false);

        RangerPolicy policy = newPolicy(ACCESS_POLICY_ID + 1, "t1", "c1");
        policy.setPolicyType(RangerPolicy.POLICY_TYPE_DATAMASK);
        policy.setDataMaskPolicyItems(Arrays.asList(ownerItem, othersItem));
        return policy;
    }

    private static RangerPolicy newPolicy(long id, String table, String column) {
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource("db1"));
        resources.put("table", new RangerPolicyResource(table));
        resources.put("column", new RangerPolicyResource(column));

        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setService(HIVE_SERVICE);
        policy.setIsEnabled(true);
        policy.setResources(resources);
        return policy;
    }

    /** the mask types of the StarRocks service definition the FE resolves the chained mask against */
    private static RangerServiceDef starRocksServiceDef() {
        List<RangerServiceDef.RangerDataMaskTypeDef> maskTypes = new ArrayList<>();
        for (String name : Arrays.asList(RangerPolicy.MASK_TYPE_NULL, RangerPolicy.MASK_TYPE_NONE)) {
            RangerServiceDef.RangerDataMaskTypeDef maskType = new RangerServiceDef.RangerDataMaskTypeDef();
            maskType.setName(name);
            maskTypes.add(maskType);
        }
        RangerServiceDef.RangerDataMaskDef dataMaskDef = new RangerServiceDef.RangerDataMaskDef();
        dataMaskDef.setMaskTypes(maskTypes);

        RangerServiceDef serviceDef = new RangerServiceDef();
        serviceDef.setName("starrocks");
        serviceDef.setDataMaskDef(dataMaskDef);
        return serviceDef;
    }

    private static RangerAccessRequestImpl request(String user, String accessType, String... levels) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue(StarRocksResourcePath.KEY_CATALOG, CATALOG);
        List<String> keys = StarRocksResourcePath.HIERARCHY.subList(1, StarRocksResourcePath.HIERARCHY.size());
        for (int i = 0; i < levels.length; i++) {
            resource.setValue(keys.get(i), levels[i]);
        }
        return new RangerAccessRequestImpl(resource, accessType, user, Collections.emptySet(), Collections.emptySet());
    }

    private static final class TestPlugin extends StarRocksHiveChainedPlugin {
        public TestPlugin(RangerBasePlugin rootPlugin) throws IOException {
            super(rootPlugin, HIVE_SERVICE);
        }

        public void setHivePolicies(RangerPolicy... policies) throws Exception {
            ServicePolicies servicePolicies = new ServicePolicies();
            servicePolicies.setServiceConfig(Collections.singletonMap(RESOURCE_OWNER_ENABLED, "true"));
            servicePolicies.setServiceName(HIVE_SERVICE);
            servicePolicies.setPolicyVersion(1L);
            servicePolicies.setServiceDef(EmbeddedServiceDefsUtil.instance()
                .getEmbeddedServiceDef(EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_HIVE_NAME));
            servicePolicies.setPolicies(Arrays.asList(policies));
            plugin.setPolicies(servicePolicies);
        }
    }
}
