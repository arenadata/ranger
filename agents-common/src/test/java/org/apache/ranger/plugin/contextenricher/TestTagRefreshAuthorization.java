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
package org.apache.ranger.plugin.contextenricher;

import org.apache.ranger.admin.client.RangerAdminClientAccessDeniedException;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;
import org.apache.ranger.plugin.util.FileUtils;
import org.apache.ranger.plugin.util.RangerLocalDirectory;
import org.apache.ranger.plugin.util.ServiceTags;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TestTagRefreshAuthorization {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private RangerTagRetriever retriever;
    private RangerTagEnricher enricher;
    private RangerPluginContext context;
    private RangerTagEnricher.RangerTagRefresher refresher;

    @Before
    public void setUp() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        Files.setPosixFilePermissions(cache.toPath(), PosixFilePermissions.fromString("rwx------"));
        File cacheFile = new File(cache, "tags.json");
        ServiceTags cachedTags = new ServiceTags();
        cachedTags.setTagVersion(7L);
        try (Writer writer = new FileWriter(cacheFile)) {
            JsonUtils.objectToWriter(writer, cachedTags);
        }
        context = new RangerPluginContext(new RangerPluginConfig("spark", "spark_test", "sparkSql", "cl1", "on-prem", new RangerPolicyEngineOptions()));
        retriever = mock(RangerTagRetriever.class);
        enricher = mock(RangerTagEnricher.class);
        when(enricher.getPluginContext()).thenReturn(context);
        RangerLocalDirectory.ResolvedDirectory directory = RangerLocalDirectory.resolve(cache.getAbsolutePath(), "disabled", FileUtils.parsePermissions("700"), FileUtils.parsePermissions("600"));
        refresher = new RangerTagEnricher.RangerTagRefresher(retriever, enricher, -1L, new LinkedBlockingQueue<>(), directory, cacheFile.getAbsolutePath(), directory.getFilePermissions());
    }

    @Test
    public void unauthorizedDoesNotLoadCache() throws Exception {
        assertDenied(401);
    }

    @Test
    public void forbiddenDoesNotLoadCache() throws Exception {
        assertDenied(403);
    }

    @Test
    public void transientFailureLoadsCacheWithoutClearingPreviousDenial() throws Exception {
        assertDenied(403);
        doThrow(new IOException("network unavailable")).when(retriever).retrieveTags(anyLong(), anyLong());
        refresh();
        assertTrue(context.isPolicyRefreshAuthzDenied());
        ArgumentCaptor<ServiceTags> captured = ArgumentCaptor.forClass(ServiceTags.class);
        verify(enricher).setServiceTags(captured.capture());
        assertEquals(Long.valueOf(7), captured.getValue().getTagVersion());
    }

    @Test
    public void successfulRefreshClearsDenial() throws Exception {
        assertDenied(403);
        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(8L);
        doReturn(tags).when(retriever).retrieveTags(anyLong(), anyLong());
        refresh();
        assertFalse(context.isPolicyRefreshAuthzDenied());
        verify(enricher).setServiceTags(tags);
    }

    @Test
    public void notModifiedDoesNotClearAnotherEndpointDenial() throws Exception {
        assertDenied(403);
        context.setRoleDownloadAuthzDenied(true);
        doReturn(null).when(retriever).retrieveTags(anyLong(), anyLong());
        refresh();
        assertTrue(context.isPolicyRefreshAuthzDenied());
        context.setRoleDownloadAuthzDenied(false);
        assertFalse(context.isPolicyRefreshAuthzDenied());
    }

    private void assertDenied(int status) throws Exception {
        doThrow(new RangerAdminClientAccessDeniedException(status, "download denied")).when(retriever).retrieveTags(anyLong(), anyLong());
        refresh();
        assertTrue(context.isPolicyRefreshAuthzDenied());
        verify(enricher, never()).setServiceTags(any());
    }

    private void refresh() throws Exception {
        Method populate = RangerTagEnricher.RangerTagRefresher.class.getDeclaredMethod("populateTags");
        populate.setAccessible(true);
        populate.invoke(refresher);
    }
}
