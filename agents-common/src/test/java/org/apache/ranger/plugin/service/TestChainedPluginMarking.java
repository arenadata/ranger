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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The merge point, driven through the public isAccessAllowed(): attribution follows an override, never mere participation, and deny is sticky. */
public class TestChainedPluginMarking {
    private static final String CHAINED_A = "cl1_hive";
    private static final String CHAINED_B = "cl1_hive_2";

    private static RangerPolicyEngineOptions peOptions;
    private static ServicePolicies           policies;

    @BeforeClass
    public static void setUpBeforeClass() {
        Gson gsonBuilder = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSSZ").create();

        peOptions = new RangerPolicyEngineOptions();

        peOptions.disablePolicyRefresher    = true;
        peOptions.disableTagRetriever       = true;
        peOptions.disableUserStoreRetriever = true;

        policies = gsonBuilder.fromJson(new InputStreamReader(TestChainedPluginMarking.class.getResourceAsStream("/plugin/hive_policies.json")), ServicePolicies.class);

        policies.getServiceDef().setMarkerAccessTypes(ServiceDefUtil.getMarkerAccessTypes(policies.getServiceDef().getAccessTypes()));
    }

    @Before
    public void resetStubs() {
        StubChainedPlugin.nextResults.clear();
        MappingStubChainedPlugin.mappedResults = null;
    }

    // ---- one chained plugin: the override predicate ----

    /** scenario 1: root allows, chained plugin denies without a matching policy - the chained verdict wins and is attributed */
    @Test
    public void chainedDenyOverridesAndIsAttributed() {
        RangerAccessResult chained = chainedResult(CHAINED_A, false, -1L, RangerPolicy.POLICY_PRIORITY_OVERRIDE);

        chained.setReason("chained says no");

        StubChainedPlugin.nextResults.put(CHAINED_A, chained);

        RangerAccessResult result = newPlugin(CHAINED_A, StubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertNotNull(result);
        assertFalse("chained deny must win", result.getIsAllowed());
        assertEquals("policyId must come from the chained plugin", -1L, result.getPolicyId());
        assertEquals(CHAINED_A, result.getChainedServiceName());
        assertEquals("the explanation must travel with the verdict it explains", "chained says no", result.getReason());
    }

    /** scenario 2: chained plugin allows with a specific policy - both the id and the author are recorded */
    @Test
    public void chainedAllowOverridesAndIsAttributed() {
        StubChainedPlugin.nextResults.put(CHAINED_A, chainedResult(CHAINED_A, true, 1042L, RangerPolicy.POLICY_PRIORITY_OVERRIDE));

        RangerAccessResult result = newPlugin(CHAINED_A, StubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertNotNull(result);
        assertTrue(result.getIsAllowed());
        assertEquals(1042L, result.getPolicyId());
        assertEquals(CHAINED_A, result.getChainedServiceName());
    }

    /** scenario 3: no resource mapping, chained plugin returns undetermined - root verdict stands, nothing is marked */
    @Test
    public void undeterminedChainedResultLeavesNoMark() {
        StubChainedPlugin.nextResults.put(CHAINED_A, new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, CHAINED_A, null, null));

        RangerAccessResult result = newPlugin(CHAINED_A, StubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertNotNull(result);
        assertTrue("root verdict must stand", result.getIsAllowed());
        assertEquals(100L, result.getPolicyId());
        assertNull("no chained plugin decided - nothing to attribute", result.getChainedServiceName());
    }

    /** a determined chained verdict that loses the override predicate must not be attributed either */
    @Test
    public void losingChainedResultLeavesNoMark() {
        // determined deny, but with normal priority and no policy id: fails every override branch
        RangerAccessResult chained = chainedResult(CHAINED_A, false, -1L, RangerPolicy.POLICY_PRIORITY_NORMAL);

        chained.setReason("chained says no");

        StubChainedPlugin.nextResults.put(CHAINED_A, chained);

        RangerAccessResult result = newPlugin(CHAINED_A, StubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertNotNull(result);
        assertTrue("root verdict must stand", result.getIsAllowed());
        assertNull("the chained verdict was discarded, so it must not be advertised", result.getChainedServiceName());
        assertNotEquals("a discarded verdict must not leave its explanation behind", "chained says no", result.getReason());
    }

    @Test
    public void denyIsStickyWhateverTheOrder() {
        StubChainedPlugin.nextResults.put(CHAINED_A, chainedResult(CHAINED_A, false, -1L, RangerPolicy.POLICY_PRIORITY_OVERRIDE));
        StubChainedPlugin.nextResults.put(CHAINED_B, chainedResult(CHAINED_B, true, 42L, RangerPolicy.POLICY_PRIORITY_OVERRIDE));

        RangerAccessResult result = newPlugin(CHAINED_A + "," + CHAINED_B, StubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertFalse("a later chained allow must not lift an earlier chained deny", result.getIsAllowed());
        assertEquals(CHAINED_A, result.getChainedServiceName());

        result = newPlugin(CHAINED_B + "," + CHAINED_A, StubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertFalse("a later chained deny must beat an earlier chained allow", result.getIsAllowed());
        assertEquals(CHAINED_A, result.getChainedServiceName());
    }

    // ---- one chained plugin, several mapped requests (e.g. hdfs write -> hive UPDATE + CREATE) ----

    @Test
    public void oneDeniedMappedRequestDeniesTheWhole() {
        // the individual hive-engine results come with normal priority; the reduction must lift the deny to the chained priority
        MappingStubChainedPlugin.mappedResults = Arrays.asList(
                chainedResult(CHAINED_A, true,  42L, RangerPolicy.POLICY_PRIORITY_NORMAL),
                chainedResult(CHAINED_A, false, -1L, RangerPolicy.POLICY_PRIORITY_NORMAL));

        RangerAccessResult result = newPlugin(CHAINED_A, MappingStubChainedPlugin.class).isAccessAllowed(allowedRequest());

        assertNotNull(result);
        assertFalse("one denied mapped request must deny the whole access", result.getIsAllowed());
        assertEquals(RangerPolicy.POLICY_PRIORITY_OVERRIDE, result.getPolicyPriority());
        assertEquals(CHAINED_A, result.getChainedServiceName());
        assertEquals(-1L, result.getPolicyId());
    }

    private RangerBasePlugin newPlugin(String chainedServices, Class<? extends RangerChainedPlugin> impl) {
        RangerPluginConfig config = new RangerPluginConfig(policies.getServiceDef().getName(), policies.getServiceName(), "hive", "cl1", "on-prem", peOptions);

        config.set(config.getPropertyPrefix() + ".chained.services", chainedServices);

        for (String chainedService : chainedServices.split(",")) {
            config.set(config.getPropertyPrefix() + ".chained.services." + chainedService + ".impl", impl.getName());
        }

        config.setBoolean(config.getPropertyPrefix() + ".bypass.chained.plugin.evaluation.if.access.is.determined", false);

        return new RangerBasePlugin(config, policies, null, null, null);
    }

    /** sales.prospects / select / res-user is allowed by policy 100 in the shared fixture */
    private RangerAccessRequest allowedRequest() {
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

    private static RangerAccessResult chainedResult(String serviceName, boolean isAllowed, long policyId, int policyPriority) {
        RangerAccessResult ret = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, serviceName, null, null);

        ret.setIsAccessDetermined(true);
        ret.setIsAllowed(isAllowed);
        ret.setPolicyId(policyId);
        ret.setPolicyPriority(policyPriority);

        return ret;
    }

    /**
     * Minimal chained plugin: returns a canned verdict per service name, so the test exercises the
     * merge predicate rather than a second policy engine. Must be public with a (RangerBasePlugin, String)
     * ctor - that is what RangerBasePlugin.initChainedPlugins() looks up reflectively.
     */
    public static class StubChainedPlugin extends RangerChainedPlugin {
        static final Map<String, RangerAccessResult> nextResults = new HashMap<>();

        public StubChainedPlugin(RangerBasePlugin rootPlugin, String serviceName) {
            super(rootPlugin, "hive", serviceName);
        }

        @Override
        protected RangerBasePlugin buildChainedPlugin(String serviceType, String serviceName, String appId) {
            return new RangerBasePlugin(new RangerPluginConfig(serviceType, serviceName, appId, "cl1", "on-prem", peOptions));
        }

        @Override
        public void init() {
            // no policy download for the stub
        }

        @Override
        public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
            return nextResults.get(serviceName);
        }

        @Override
        public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests) {
            return null;
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

    /**
     * Real ResourceMappingChainedPlugin reduction over canned per-request results: one root request
     * maps to two chained requests, and the chained engine is stubbed to answer them.
     */
    public static class MappingStubChainedPlugin extends ResourceMappingChainedPlugin {
        static List<RangerAccessResult> mappedResults;

        public MappingStubChainedPlugin(RangerBasePlugin rootPlugin, String serviceName) {
            super(rootPlugin, "hive", serviceName);
        }

        @Override
        protected RangerBasePlugin buildChainedPlugin(String serviceType, String serviceName, String appId) {
            return new RangerBasePlugin(new RangerPluginConfig(serviceType, serviceName, appId, "cl1", "on-prem", peOptions)) {
                @Override
                public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests, RangerAccessResultProcessor resultProcessor) {
                    return mappedResults;
                }
            };
        }

        @Override
        public void init() {
            // no policy download for the stub
        }

        @Override
        protected List<RangerAccessRequest> toChainedRequests(RangerAccessRequest request) {
            return Arrays.asList(request, request);
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
