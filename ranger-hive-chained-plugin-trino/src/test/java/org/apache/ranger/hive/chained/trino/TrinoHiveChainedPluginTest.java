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

import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerDataMaskPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemDataMaskInfo;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {OWNER} in Hive policies applies to Trino requests checked by the chained Hive plugin: the owner of the Hive table
 * is the owner Trino reports for its table.
 */
public class TrinoHiveChainedPluginTest
{
    private static final String HIVE_SERVICE = "dev_hive";
    private static final String OWNER = "alice";
    private static final String OTHER_USER = "bob";
    private static final String SCHEMA = "sch1";
    private static final String TABLE = "tbl1";
    private static final String COLUMN = "col1";
    private static final long POLICY_ID = 10;

    @Test
    public void testTableOwnerIsAllowedByOwnerPolicy()
            throws Exception
    {
        TestPlugin plugin = newPlugin(accessPolicy(RangerPolicyEngine.RESOURCE_OWNER, "select", "update"));

        RangerAccessResult select = plugin.isAccessAllowed(request(table(OWNER), OWNER, "select"));
        assertTrue(select.getIsAccessDetermined());
        assertTrue(select.getIsAllowed());
        assertEquals(POLICY_ID, select.getPolicyId());
        assertEquals(HIVE_SERVICE, select.getServiceName());

        assertTrue(plugin.isAccessAllowed(request(table(OWNER), OWNER, "insert")).getIsAllowed());
        assertTrue(plugin.isAccessAllowed(request(table(OWNER), OWNER, "delete")).getIsAllowed());
    }

    @Test
    public void testOtherUserIsNotAllowedByOwnerPolicy()
            throws Exception
    {
        TestPlugin plugin = newPlugin(accessPolicy(RangerPolicyEngine.RESOURCE_OWNER, "select"));

        RangerAccessResult result = plugin.isAccessAllowed(request(table(OWNER), OTHER_USER, "select"));

        assertFalse(result.getIsAllowed());
    }

    @Test
    public void testUnknownOwnerIsNotAllowedByOwnerPolicy()
            throws Exception
    {
        TestPlugin plugin = newPlugin(accessPolicy(RangerPolicyEngine.RESOURCE_OWNER, "select"));

        assertFalse(plugin.isAccessAllowed(request(table(null), OWNER, "select")).getIsAllowed());
    }

    @Test
    public void testColumnOwnerIsTableOwner()
            throws Exception
    {
        TestPlugin plugin = newPlugin(accessPolicy(RangerPolicyEngine.RESOURCE_OWNER, "select"));

        assertTrue(plugin.isAccessAllowed(request(column(OWNER), OWNER, "select")).getIsAllowed());
        assertFalse(plugin.isAccessAllowed(request(column(OWNER), OTHER_USER, "select")).getIsAllowed());
    }

    @Test
    public void testDataMaskOfOwnerPolicy()
            throws Exception
    {
        TestPlugin plugin = newPlugin(maskPolicy());

        RangerAccessResult owner = plugin.evalDataMaskPolicies(request(column(OWNER), OWNER, "select"));
        assertEquals(RangerPolicy.MASK_TYPE_NONE, owner.getMaskType());

        RangerAccessResult other = plugin.evalDataMaskPolicies(request(column(OWNER), OTHER_USER, "select"));
        assertEquals(RangerPolicy.MASK_TYPE_NULL, other.getMaskType());
    }

    @Test
    public void testOwnerIsNotReadWithoutOwnerPolicy()
            throws Exception
    {
        TestPlugin plugin = newPlugin(accessPolicy(OWNER, "select"));
        TrinoResource table = table(OWNER);

        assertTrue(plugin.isAccessAllowed(request(table, OWNER, "select")).getIsAllowed());
        assertFalse(plugin.isAccessAllowed(request(table, OTHER_USER, "select")).getIsAllowed());
        assertEquals(0, table.ownerReads);
    }

    private static TestPlugin newPlugin(RangerPolicy policy)
            throws Exception
    {
        RangerBasePlugin trinoPlugin = new RangerBasePlugin(new RangerPluginConfig("trino", "dev_trino", "trino", null, null, null));
        TestPlugin plugin = new TestPlugin(trinoPlugin);

        ServicePolicies policies = new ServicePolicies();
        policies.setServiceName(HIVE_SERVICE);
        policies.setPolicyVersion(1L);
        policies.setServiceDef(EmbeddedServiceDefsUtil.instance().getEmbeddedServiceDef(EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_HIVE_NAME));
        policies.setPolicies(Collections.singletonList(policy));
        plugin.setHivePolicies(policies);
        return plugin;
    }

    private static RangerPolicy accessPolicy(String user, String... accessTypes)
    {
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(Collections.singletonList(user));
        item.setAccesses(accesses(accessTypes));

        RangerPolicy policy = newPolicy("*");
        policy.setPolicyItems(Collections.singletonList(item));
        return policy;
    }

    private static RangerPolicy maskPolicy()
    {
        RangerDataMaskPolicyItem ownerItem = new RangerDataMaskPolicyItem(accesses("select"),
                new RangerPolicyItemDataMaskInfo(RangerPolicy.MASK_TYPE_NONE, null, null),
                Collections.singletonList(RangerPolicyEngine.RESOURCE_OWNER), null, null, null, false);
        RangerDataMaskPolicyItem othersItem = new RangerDataMaskPolicyItem(accesses("select"),
                new RangerPolicyItemDataMaskInfo(RangerPolicy.MASK_TYPE_NULL, null, null),
                null, Collections.singletonList(RangerPolicyEngine.GROUP_PUBLIC), null, null, false);

        RangerPolicy policy = newPolicy(COLUMN);
        policy.setPolicyType(RangerPolicy.POLICY_TYPE_DATAMASK);
        policy.setDataMaskPolicyItems(Arrays.asList(ownerItem, othersItem));
        return policy;
    }

    private static RangerPolicy newPolicy(String column)
    {
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource(SCHEMA));
        resources.put("table", new RangerPolicyResource(TABLE));
        resources.put("column", new RangerPolicyResource(column));

        RangerPolicy policy = new RangerPolicy();
        policy.setId(POLICY_ID);
        policy.setName("owner-policy");
        policy.setService(HIVE_SERVICE);
        policy.setIsEnabled(true);
        policy.setResources(resources);
        return policy;
    }

    private static List<RangerPolicyItemAccess> accesses(String... accessTypes)
    {
        RangerPolicyItemAccess[] accesses = Arrays.stream(accessTypes)
                .map(accessType -> new RangerPolicyItemAccess(accessType, true))
                .toArray(RangerPolicyItemAccess[]::new);
        return Arrays.asList(accesses);
    }

    private static TrinoResource table(String owner)
    {
        TrinoResource resource = new TrinoResource(owner);
        resource.setValue("catalog", "hive");
        resource.setValue("schema", SCHEMA);
        resource.setValue("table", TABLE);
        return resource;
    }

    private static TrinoResource column(String owner)
    {
        TrinoResource resource = table(owner);
        resource.setValue("column", COLUMN);
        return resource;
    }

    private static RangerAccessRequestImpl request(TrinoResource resource, String user, String accessType)
    {
        return new RangerAccessRequestImpl(resource, accessType, user, Collections.emptySet(), Collections.emptySet());
    }

    /**
     * Trino resource as the Trino plugin builds it; the owner is counted because Trino reads it from the metastore.
     */
    private static final class TrinoResource
            extends RangerAccessResourceImpl
    {
        private final String owner;
        private int ownerReads;

        public TrinoResource(String owner)
        {
            this.owner = owner;
        }

        @Override
        public String getOwnerUser()
        {
            ownerReads++;
            return owner;
        }
    }

    private static final class TestPlugin
            extends TrinoHiveChainedPlugin
    {
        public TestPlugin(RangerBasePlugin trinoPlugin)
        {
            super(trinoPlugin, HIVE_SERVICE);
        }

        public void setHivePolicies(ServicePolicies policies)
        {
            plugin.setPolicies(policies);
        }
    }
}
