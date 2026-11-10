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

package org.apache.ranger.plugin.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.policyengine.RangerResourceACLs;
import org.apache.ranger.plugin.util.ServiceDefUtil;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Which chained results may override the root service.
 *
 * <p>A resource mapped onto one chained request is the normal case for a data mask: the plugin that
 * asks for it sends one request per column. For an access check {@code isAllowed == false} is a
 * verdict and must win; for a mask or a row filter it only means "no matching policy in the chained
 * service", and raising it to override priority would erase the mask the root service defined.
 */
public class TestResourceMappingChainedPluginPriority {
    private static final String CHAINED = "cl1_hive";

    private static RangerPolicyEngineOptions peOptions;
    private static ServicePolicies           policies;

    @BeforeClass
    public static void setUpBeforeClass() {
        Gson gsonBuilder = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSSZ").create();

        peOptions = new RangerPolicyEngineOptions();

        peOptions.disablePolicyRefresher    = true;
        peOptions.disableTagRetriever       = true;
        peOptions.disableUserStoreRetriever = true;

        policies = gsonBuilder.fromJson(new InputStreamReader(TestResourceMappingChainedPluginPriority.class.getResourceAsStream("/plugin/hive_policies.json")), ServicePolicies.class);

        policies.getServiceDef().setMarkerAccessTypes(ServiceDefUtil.getMarkerAccessTypes(policies.getServiceDef().getAccessTypes()));
    }

    @Before
    public void resetStubs() {
        StubPlugin.accessResults     = null;
        StubPlugin.maskResults       = null;
        StubPlugin.rowFilterResults  = null;
        StubPlugin.requestsPerResource = 1;
    }

    // ---- one mapped request ----

    /** the regression: a mask evaluation that matched nothing must not outrank the root service */
    @Test
    public void singleMaskWithoutAPolicyKeepsNormalPriority() {
        StubPlugin.maskResults = Collections.singletonList(result(RangerPolicy.POLICY_TYPE_DATAMASK, false, -1L));

        RangerAccessResult result = chainedPlugin().evalDataMaskPolicies(request());

        assertNotNull(result);
        assertFalse(result.getIsAllowed());
        assertEquals(RangerPolicy.POLICY_PRIORITY_NORMAL, result.getPolicyPriority());
    }

    @Test
    public void singleRowFilterWithoutAPolicyKeepsNormalPriority() {
        StubPlugin.rowFilterResults = Collections.singletonList(result(RangerPolicy.POLICY_TYPE_ROWFILTER, false, -1L));

        RangerAccessResult result = chainedPlugin().evalRowFilterPolicies(request());

        assertNotNull(result);
        assertEquals(RangerPolicy.POLICY_PRIORITY_NORMAL, result.getPolicyPriority());
    }

    /** a mask that did match must still override the root service's own mask */
    @Test
    public void singleMatchedMaskIsRaisedToOverride() {
        RangerAccessResult masked = result(RangerPolicy.POLICY_TYPE_DATAMASK, true, 7L);
        masked.setMaskType("MASK");

        StubPlugin.maskResults = Collections.singletonList(masked);

        RangerAccessResult result = chainedPlugin().evalDataMaskPolicies(request());

        assertEquals("MASK", result.getMaskType());
        assertEquals(RangerPolicy.POLICY_PRIORITY_OVERRIDE, result.getPolicyPriority());
    }

    @Test
    public void singleMatchedRowFilterIsRaisedToOverride() {
        RangerAccessResult filtered = result(RangerPolicy.POLICY_TYPE_ROWFILTER, true, 9L);
        filtered.setFilterExpr("region = 'eu'");

        StubPlugin.rowFilterResults = Collections.singletonList(filtered);

        RangerAccessResult result = chainedPlugin().evalRowFilterPolicies(request());

        assertEquals("region = 'eu'", result.getFilterExpr());
        assertEquals(RangerPolicy.POLICY_PRIORITY_OVERRIDE, result.getPolicyPriority());
    }

    /** an access check is a verdict either way, so both outcomes still override */
    @Test
    public void singleAccessDenialIsRaisedToOverride() {
        StubPlugin.accessResults = Collections.singletonList(result(RangerPolicy.POLICY_TYPE_ACCESS, false, -1L));

        RangerAccessResult result = chainedPlugin().isAccessAllowed(request());

        assertFalse(result.getIsAllowed());
        assertEquals(RangerPolicy.POLICY_PRIORITY_OVERRIDE, result.getPolicyPriority());
    }

    @Test
    public void singleAccessGrantIsRaisedToOverride() {
        StubPlugin.accessResults = Collections.singletonList(result(RangerPolicy.POLICY_TYPE_ACCESS, true, 42L));

        RangerAccessResult result = chainedPlugin().isAccessAllowed(request());

        assertTrue(result.getIsAllowed());
        assertEquals(RangerPolicy.POLICY_PRIORITY_OVERRIDE, result.getPolicyPriority());
    }

    // ---- several mapped requests ----

    /** the same rule after the reduction of several mapped requests */
    @Test
    public void reducedMaskWithoutAPolicyKeepsNormalPriority() {
        StubPlugin.requestsPerResource = 2;
        StubPlugin.maskResults = Arrays.asList(
            result(RangerPolicy.POLICY_TYPE_DATAMASK, false, -1L),
            result(RangerPolicy.POLICY_TYPE_DATAMASK, false, -1L));

        RangerAccessResult result = chainedPlugin().evalDataMaskPolicies(request());

        assertEquals(RangerPolicy.POLICY_PRIORITY_NORMAL, result.getPolicyPriority());
    }

    @Test
    public void reducedAccessDenialIsRaisedToOverride() {
        StubPlugin.requestsPerResource = 2;
        StubPlugin.accessResults = Arrays.asList(
            result(RangerPolicy.POLICY_TYPE_ACCESS, true, 42L),
            result(RangerPolicy.POLICY_TYPE_ACCESS, false, -1L));

        RangerAccessResult result = chainedPlugin().isAccessAllowed(request());

        assertFalse(result.getIsAllowed());
        assertEquals(RangerPolicy.POLICY_PRIORITY_OVERRIDE, result.getPolicyPriority());
    }

    // ---- no mapping at all ----

    @Test
    public void anUnmappedResourceLeavesTheRootVerdictAlone() {
        StubPlugin.requestsPerResource = 0;

        RangerAccessResult result = chainedPlugin().evalDataMaskPolicies(request());

        assertFalse("nothing was mapped, so there is nothing to override", result.getIsAccessDetermined());
    }

    // ---- helpers ----

    private RangerChainedPlugin chainedPlugin() {
        RangerPluginConfig config = new RangerPluginConfig(policies.getServiceDef().getName(), policies.getServiceName(), "hive", "cl1", "on-prem", peOptions);

        config.set(config.getPropertyPrefix() + ".chained.services", CHAINED);
        config.set(config.getPropertyPrefix() + ".chained.services." + CHAINED + ".impl", StubPlugin.class.getName());
        config.setBoolean(config.getPropertyPrefix() + ".bypass.chained.plugin.evaluation.if.access.is.determined", false);

        return new RangerBasePlugin(config, policies, null, null, null).getChainedPlugins().get(0);
    }

    private RangerAccessRequest request() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();

        resource.setServiceDef(policies.getServiceDef());
        resource.setValue("database", "sales");
        resource.setValue("table", "prospects");

        RangerAccessRequestImpl ret = new RangerAccessRequestImpl();

        ret.setResource(resource);
        ret.setAccessType("select");
        ret.setUser("res-user");
        ret.setAccessTime(new Date());

        return ret;
    }

    private static RangerAccessResult result(int policyType, boolean isAllowed, long policyId) {
        RangerAccessResult ret = new RangerAccessResult(policyType, CHAINED, null, null);

        ret.setIsAccessDetermined(true);
        ret.setIsAllowed(isAllowed);
        ret.setPolicyId(policyId);
        ret.setPolicyPriority(RangerPolicy.POLICY_PRIORITY_NORMAL);

        return ret;
    }

    /**
     * Real {@link ResourceMappingChainedPlugin} over a chained engine that answers with canned
     * results, so the test exercises the reduction and the priority rule rather than a second policy
     * engine. Must be public with a (RangerBasePlugin, String) ctor, that is what
     * {@code RangerBasePlugin.initChainedPlugins()} looks up reflectively.
     */
    public static class StubPlugin extends ResourceMappingChainedPlugin {
        static List<RangerAccessResult> accessResults;
        static List<RangerAccessResult> maskResults;
        static List<RangerAccessResult> rowFilterResults;
        static int requestsPerResource = 1;

        public StubPlugin(RangerBasePlugin rootPlugin, String serviceName) {
            super(rootPlugin, "hive", serviceName);
        }

        @Override
        protected RangerBasePlugin buildChainedPlugin(String serviceType, String serviceName, String appId) {
            return new RangerBasePlugin(new RangerPluginConfig(serviceType, serviceName, appId, "cl1", "on-prem", peOptions)) {
                private int maskIndex;
                private int rowFilterIndex;

                @Override
                public RangerAccessResult isAccessAllowed(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                    return accessResults.get(0);
                }

                @Override
                public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests, RangerAccessResultProcessor resultProcessor) {
                    return accessResults;
                }

                @Override
                public RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                    return maskResults.get(maskIndex++);
                }

                @Override
                public RangerAccessResult evalRowFilterPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
                    return rowFilterResults.get(rowFilterIndex++);
                }
            };
        }

        @Override
        public void init() {
            // no policy download for the stub
        }

        @Override
        protected List<RangerAccessRequest> toChainedRequests(RangerAccessRequest request) {
            return Collections.nCopies(requestsPerResource, request);
        }

        @Override
        public RangerResourceACLs getResourceACLs(RangerAccessRequest request) {
            return null;
        }

        @Override
        public RangerResourceACLs getResourceACLs(RangerAccessRequest request, Integer policyType) {
            return null;
        }
    }
}
