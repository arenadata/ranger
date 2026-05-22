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

/**
 * Tests for Trino plugin policy evaluation using {@link RangerBasePlugin}.
 *
 * <p>Loads policies from {@code trino-policies.json} and verifies that the
 * {@code execute} permission works correctly for {@code schemafunction} resources
 * (needed for Trino pushdown queries like {@code SELECT * FROM TABLE(oracle.system.query(...))}).
 *
 * <p>Also includes regression checks for {@code execute} on {@code procedure} and {@code function}.
 */
package org.apache.ranger.authorization.trino.authorizer;

import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.RangerRoles;
import org.apache.ranger.plugin.util.RangerUserStore;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerTrinoPluginTest {
    private static RangerBasePlugin plugin;

    @BeforeAll
    static void setUp() {
        Gson gson = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSS-Z")
                .setPrettyPrinting()
                .create();

        InputStream inStream = RangerTrinoPluginTest.class.getResourceAsStream("/trino-policies.json");
        assertNotNull(inStream, "trino-policies.json not found on classpath");

        ServicePolicies policies = gson.fromJson(new InputStreamReader(inStream), ServicePolicies.class);
        assertNotNull(policies, "failed to parse ServicePolicies");

        RangerPolicyEngineOptions peOptions = new RangerPolicyEngineOptions();
        peOptions.disablePolicyRefresher    = true;
        peOptions.disableTagRetriever       = true;
        peOptions.disableUserStoreRetriever = true;

        RangerPluginConfig pluginConfig = new RangerPluginConfig("trino", "cl1_trino", "trino", "cl1", "on-prem", peOptions);
        plugin = new RangerBasePlugin(pluginConfig, policies, null, new RangerRoles(), new RangerUserStore());
    }

    /** User alice has an explicit policy granting execute on schemafunction=query — access should be allowed. */
    @Test
    void aliceExecuteSchemaFunctionAllowed() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("catalog", "alice-catalog");
        resource.setValue("schema", "schema");
        resource.setValue("schemafunction", "query");

        RangerAccessRequestImpl request = new RangerAccessRequestImpl(resource, "execute", "alice", null, null);
        request.setAccessTime(new Date());

        RangerAccessResult result = plugin.isAccessAllowed(request);

        assertNotNull(result, "result should not be null");
        assertTrue(result.getIsAllowed(), "alice should be allowed to execute schemafunction=query");
    }

    /** User bob has no policy for schemafunction — access should be denied. */
    @Test
    void bobExecuteSchemaFunctionDenied() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("catalog", "alice-catalog");
        resource.setValue("schema", "schema");
        resource.setValue("schemafunction", "query");

        RangerAccessRequestImpl request = new RangerAccessRequestImpl(resource, "execute", "bob", null, null);
        request.setAccessTime(new Date());

        RangerAccessResult result = plugin.isAccessAllowed(request);

        assertNotNull(result, "result should not be null");
        assertFalse(result.getIsAllowed(), "bob should NOT be allowed to execute schemafunction=query");
    }

    /** Regression: execute on procedure must still work after adding schemafunction support. */
    @Test
    void aliceExecuteProcedureAllowed() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("catalog", "alice-catalog");
        resource.setValue("schema", "schema");
        resource.setValue("procedure", "procedure");

        RangerAccessRequestImpl request = new RangerAccessRequestImpl(resource, "execute", "alice", null, null);
        request.setAccessTime(new Date());

        RangerAccessResult result = plugin.isAccessAllowed(request);

        assertNotNull(result, "result should not be null");
        assertTrue(result.getIsAllowed(), "alice should be allowed to execute procedure (regression check)");
    }

    /** Regression: execute on function must still work after adding schemafunction support. */
    @Test
    void aliceExecuteFunctionAllowed() {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue("function", "function");

        RangerAccessRequestImpl request = new RangerAccessRequestImpl(resource, "execute", "alice", null, null);
        request.setAccessTime(new Date());

        RangerAccessResult result = plugin.isAccessAllowed(request);

        assertNotNull(result, "result should not be null");
        assertTrue(result.getIsAllowed(), "alice should be allowed to execute function (regression check)");
    }
}
