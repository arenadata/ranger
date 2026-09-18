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
package org.apache.ranger.plugin.util;

import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.admin.client.RangerAdminClientAccessDeniedException;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class TestPolicyRefreshAuthorization {
    @Parameterized.Parameters(name = "roles={0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final boolean roles;
    private RangerAdminClient admin;
    private RangerBasePlugin plugin;
    private RangerPluginContext context;
    private PolicyRefresher policyRefresher;
    private RangerRolesProvider rolesProvider;

    public TestPolicyRefreshAuthorization(boolean roles) {
        this.roles = roles;
    }

    @Before
    public void setUp() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        Files.setPosixFilePermissions(cache.toPath(), PosixFilePermissions.fromString("rwx------"));
        RangerPluginConfig config = new RangerPluginConfig("spark", "spark_test", "sparkSql", "cl1", "on-prem", new RangerPolicyEngineOptions());
        config.set("ranger.plugin.spark.policy.cache.dir", cache.getAbsolutePath());
        config.set("ranger.plugin.spark.policy.cache.dir.perms", "700");
        config.set("ranger.plugin.spark.policy.cache.subdir.mode", "disabled");
        admin = mock(RangerAdminClient.class);
        context = new RangerPluginContext(config);
        context.setAdminClient(admin);
        plugin = mock(RangerBasePlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServiceType()).thenReturn("spark");
        when(plugin.getServiceName()).thenReturn("spark_test");
        when(plugin.getAppId()).thenReturn("sparkSql");
        when(plugin.getPluginContext()).thenReturn(context);
        policyRefresher = new PolicyRefresher(plugin);
        rolesProvider = new RangerRolesProvider("spark", "sparkSql", "spark_test", admin, cache.getAbsolutePath(), config);

        ServicePolicies policies = new ServicePolicies();
        policies.setServiceName("spark_test");
        policies.setPolicyVersion(7L);
        RangerRoles cachedRoles = new RangerRoles();
        cachedRoles.setRoleVersion(7L);
        try (Writer writer = new FileWriter(new File(cache, "sparkSql_spark_test.json"))) {
            JsonUtils.objectToWriter(writer, policies);
        }
        try (Writer writer = new FileWriter(new File(cache, "sparkSql_spark_test_roles.json"))) {
            JsonUtils.objectToWriter(writer, cachedRoles);
        }
    }

    @Test
    public void unauthorizedDoesNotLoadCachedData() throws Exception {
        assertDenialSkipsCache(401);
    }

    @Test
    public void forbiddenDoesNotLoadCachedData() throws Exception {
        assertDenialSkipsCache(403);
    }

    @Test
    public void transientFailureCanLoadLastKnownCache() throws Exception {
        failDownload(new IOException("network unavailable"));
        refresh();
        assertFalse(context.isPolicyRefreshAuthzDenied());
        if (roles) {
            ArgumentCaptor<RangerRoles> captured = ArgumentCaptor.forClass(RangerRoles.class);
            verify(plugin).setRoles(captured.capture());
            assertEquals(Long.valueOf(7), captured.getValue().getRoleVersion());
        } else {
            ArgumentCaptor<ServicePolicies> captured = ArgumentCaptor.forClass(ServicePolicies.class);
            verify(plugin).setPolicies(captured.capture());
            assertEquals(Long.valueOf(7), captured.getValue().getPolicyVersion());
        }
    }

    @Test
    public void transientFailureDoesNotClearPreviousDenial() throws Exception {
        assertDenialSkipsCache(403);
        failDownload(new IOException("network unavailable"));
        refresh();
        assertTrue(context.isPolicyRefreshAuthzDenied());
    }

    @Test
    public void successfulRefreshClearsDenial() throws Exception {
        assertDenialSkipsCache(403);
        if (roles) {
            doReturn(new RangerRoles()).when(admin).getRolesIfUpdated(anyLong(), anyLong());
        } else {
            ServicePolicies policies = new ServicePolicies();
            policies.setServiceName("spark_test");
            doReturn(policies).when(admin).getServicePoliciesIfUpdated(anyLong(), anyLong());
        }
        refresh();
        assertFalse(context.isPolicyRefreshAuthzDenied());
    }

    @Test
    public void notModifiedClearsOnlyItsOwnDenial() throws Exception {
        assertDenialSkipsCache(403);
        context.setTagDownloadAuthzDenied(true);
        if (roles) {
            doReturn(null).when(admin).getRolesIfUpdated(anyLong(), anyLong());
        } else {
            doReturn(null).when(admin).getServicePoliciesIfUpdated(anyLong(), anyLong());
        }
        refresh();
        assertTrue(context.isPolicyRefreshAuthzDenied());
        context.setTagDownloadAuthzDenied(false);
        assertFalse(context.isPolicyRefreshAuthzDenied());
    }

    private void assertDenialSkipsCache(int status) throws Exception {
        failDownload(new RangerAdminClientAccessDeniedException(status, "download denied"));
        refresh();
        assertTrue(context.isPolicyRefreshAuthzDenied());
        verify(plugin, never()).setPolicies(any());
        verify(plugin, never()).setRoles(any());
    }

    private void failDownload(Exception failure) throws Exception {
        if (roles) {
            doThrow(failure).when(admin).getRolesIfUpdated(anyLong(), anyLong());
        } else {
            doThrow(failure).when(admin).getServicePoliciesIfUpdated(anyLong(), anyLong());
        }
    }

    private void refresh() throws Exception {
        if (roles) {
            rolesProvider.loadUserGroupRoles(plugin);
        } else {
            Method loadPolicy = PolicyRefresher.class.getDeclaredMethod("loadPolicy");
            loadPolicy.setAccessible(true);
            loadPolicy.invoke(policyRefresher);
        }
    }
}
