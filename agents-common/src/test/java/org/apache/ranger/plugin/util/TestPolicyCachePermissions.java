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
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.contextenricher.RangerAdminTagRetriever;
import org.apache.ranger.plugin.contextenricher.RangerTagEnricher;
import org.apache.ranger.plugin.model.RangerPolicyDelta;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class TestPolicyCachePermissions {
    private static final String PREFIX = "ranger.plugin.cachecompat.policy.cache";

    @Parameterized.Parameters(name = "cache={0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {{"policies"}, {"roles"}, {"tags"}});
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final String kind;
    private RangerPluginConfig config;
    private Path cacheDir;
    private Path cacheFile;

    public TestPolicyCachePermissions(String kind) {
        this.kind = kind;
    }

    @Before
    public void setUp() throws Exception {
        config = new RangerPluginConfig("cachecompat", "cache_test", "test", null, null, new RangerPolicyEngineOptions());
        cacheDir = temporaryFolder.newFolder("cache").toPath();
        String suffix = "roles".equals(kind) ? "_roles" : "tags".equals(kind) ? "_tag" : "";
        cacheFile = cacheDir.resolve("test_cache_test" + suffix + ".json");
        config.set(PREFIX + ".dir", cacheDir.toString());
    }

    @Test
    public void unsetPermissionsPreserveExistingDirectoryAndFile() throws Exception {
        assertExistingPermissionsPreserved();
    }

    @Test
    public void blankPermissionsAndDisabledModePreserveExistingPermissions() throws Exception {
        config.set(PREFIX + ".subdir.mode", "disabled");
        config.set(PREFIX + ".dir.perms", " ");
        config.set(PREFIX + ".file.perms", " ");
        assertExistingPermissionsPreserved();
    }

    @Test
    public void newDirectoryAndFileUseLegacyCreationPermissions() throws Exception {
        Files.delete(cacheDir);
        File referenceDir = new File(temporaryFolder.getRoot(), "reference");
        assertTrue(referenceDir.mkdirs());
        File referenceFile = new File(referenceDir, "reference.json");
        assertTrue(referenceFile.createNewFile());

        saveCache(false);

        assertTrue(Files.size(cacheFile) > 0);
        assertEquals(Files.getPosixFilePermissions(referenceDir.toPath()), Files.getPosixFilePermissions(cacheDir));
        assertEquals(Files.getPosixFilePermissions(referenceFile.toPath()), Files.getPosixFilePermissions(cacheFile));
    }

    @Test
    public void explicitFilePermissionsDoNotRequireDirectoryPermissions() throws Exception {
        config.set(PREFIX + ".file.perms", "600");
        Files.setPosixFilePermissions(cacheDir, PosixFilePermissions.fromString("rwxrwx---"));

        saveCache(false);

        assertTrue(Files.size(cacheFile) > 0);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(cacheFile));
        assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(cacheDir));
    }

    @Test
    public void explicitDirectoryPermissionsDoNotChangeExistingFilePermissions() throws Exception {
        config.set(PREFIX + ".dir.perms", "770");
        assertExistingPermissionsPreserved();
    }

    @Test
    public void missingCachePathDoesNotCreateCache() throws Exception {
        config.unset(PREFIX + ".dir");

        saveCache(false);

        assertFalse(Files.exists(cacheFile));
        assertArrayEquals(new String[0], cacheDir.toFile().list());
    }

    @Test
    public void policyDeltaAndBackupFilesAlsoUseLegacyPermissions() throws Exception {
        org.junit.Assume.assumeTrue("policies".equals(kind));
        config.set("ranger.plugin.cachecompat.preserve.deltas", "true");
        Files.setPosixFilePermissions(cacheDir, PosixFilePermissions.fromString("rwxrwx---"));
        File reference = new File(temporaryFolder.getRoot(), "reference");
        assertTrue(reference.createNewFile());

        saveCache(false);
        saveCache(true);

        for (Path file : Arrays.asList(cacheFile, cacheDir.resolve("test_cache_test.json_2"),
                cacheDir.resolve("deltas/test_cache_test.json_2"))) {
            assertTrue(Files.size(file) > 0);
            assertEquals(Files.getPosixFilePermissions(reference.toPath()), Files.getPosixFilePermissions(file));
        }
    }

    private void assertExistingPermissionsPreserved() throws Exception {
        Files.setPosixFilePermissions(cacheDir, PosixFilePermissions.fromString("rwxrwx---"));
        Files.createFile(cacheFile);
        Files.setPosixFilePermissions(cacheFile, PosixFilePermissions.fromString("rw-------"));

        saveCache(false);

        assertTrue(Files.size(cacheFile) > 0);
        assertEquals(PosixFilePermissions.fromString("rwxrwx---"), Files.getPosixFilePermissions(cacheDir));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(cacheFile));
    }

    private void saveCache(boolean delta) throws Exception {
        RangerAdminClient admin = mock(RangerAdminClient.class);
        RangerPluginContext context = new RangerPluginContext(config);
        context.setAdminClient(admin);

        if ("policies".equals(kind)) {
            RangerBasePlugin plugin = mock(RangerBasePlugin.class);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServiceType()).thenReturn("cachecompat");
            when(plugin.getServiceName()).thenReturn("cache_test");
            when(plugin.getAppId()).thenReturn("test");
            when(plugin.getPluginContext()).thenReturn(context);
            ServicePolicies policies = new ServicePolicies();
            policies.setServiceName("cache_test");
            policies.setPolicyVersion(2L);
            if (delta) {
                policies.setPolicyDeltas(Collections.singletonList(new RangerPolicyDelta()));
            }
            new PolicyRefresher(plugin).saveToCache(policies);
        } else if ("roles".equals(kind)) {
            RangerRoles roles = new RangerRoles();
            roles.setRoleVersion(2L);
            new RangerRolesProvider("cachecompat", "test", "cache_test", admin,
                    config.get(PREFIX + ".dir"), config).saveToCache(roles);
        } else {
            ServiceTags tags = new ServiceTags();
            tags.setTagVersion(2L);
            when(admin.getServiceTagsIfUpdated(anyLong(), anyLong())).thenReturn(tags);
            RangerServiceDef serviceDef = new RangerServiceDef();
            serviceDef.setName("cachecompat");
            RangerServiceDef.RangerContextEnricherDef enricherDef = new RangerServiceDef.RangerContextEnricherDef();
            enricherDef.setEnricherOptions(Collections.singletonMap(RangerTagEnricher.TAG_RETRIEVER_CLASSNAME_OPTION,
                    RangerAdminTagRetriever.class.getName()));
            RangerTagEnricher enricher = new RangerTagEnricher();
            enricher.setPluginContext(context);
            enricher.setServiceDef(serviceDef);
            enricher.setServiceName("cache_test");
            enricher.setAppId("test");
            enricher.setEnricherDef(enricherDef);
            try {
                enricher.init();
            } finally {
                enricher.cleanup();
            }
        }
    }
}
