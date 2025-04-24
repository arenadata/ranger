/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.audit.rotation.hdfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Test;

public class CompositeLogsSelectorTest extends BaseHdfsTest {

    private static final long CURRENT_TIME_MS = 100L;
    private static final long DEFAULT_RETENTION_SIZE_BYTES = 55L;
    private static final long DEFAULT_RETENTION_PERIOD_MS = 50L;

    private CompositeStaleLogsSelector staleLogsSelector;

    @Before
    public void initSelector() {
        staleLogsSelector = new CompositeStaleLogsSelector(
            new ExpiredLogsSelector(
                DEFAULT_RETENTION_PERIOD_MS,
                () -> CURRENT_TIME_MS
            ),
            new StaleBySizeLogsSelector(DEFAULT_RETENTION_SIZE_BYTES)
        );
    }

    @Test
    public void testGetStaleFilesBySizeAndTime() {
        Set<FileStatus> logFiles = Sets.newHashSet(
            //expired by modification time
            createFileStatus("/logs/log_1", 10, 1L, false),
            //expired by modification time
            createFileStatus("/logs/log_2", 15, 5L, false),
            createFileStatus("/logs/log_3", 15, 51L, false),
            //should be deleted by size retention policy
            createFileStatus("/logs/log_4", 20, 61L, false),
            //should be deleted by size retention policy
            createFileStatus("/logs/log_5", 10, 77L, false),
            //expired by modification time
            createFileStatus("/logs/log_6", 15, 1L, false),
            createFileStatus("/logs/log_7", 37, 55L, false),
            //expired by modification time
            createFileStatus("/logs/log_8", 10, 4L, false),

            createFileStatus("/logs/some_dir1", 0, 4L, true),
            createFileStatus("/logs/some_dir2", 10, 5L, true)
        );

        Set<String> expectedPaths = Sets.newHashSet(
            "/logs/log_1",
            "/logs/log_2",
            "/logs/log_4",
            "/logs/log_5",
            "/logs/log_6",
            "/logs/log_8"
        );

        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(logFiles);
        assertEquals(expectedPaths, extractRelativePaths(actualExpiredFiles));
    }

    @Test
    public void testGetStaleFilesBySize() {
        Set<FileStatus> logFiles = Sets.newHashSet(
            createFileStatus("/logs/log_1", 1, 90L, false),
            createFileStatus("/logs/log_2", 2, 91L, false),
            createFileStatus("/logs/log_3", 51, 92L, false),
            createFileStatus("/logs/log_4", 20, 93L, false),
            createFileStatus("/logs/log_5", 10, 94L, false)
        );

        Set<String> expectedPaths = Sets.newHashSet(
            "/logs/log_4",
            "/logs/log_5"
        );

        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(logFiles);
        assertEquals(expectedPaths, extractRelativePaths(actualExpiredFiles));
    }

    @Test
    public void testGetStaleFilesByTime() {
        Set<FileStatus> logFiles = Sets.newHashSet(
            createFileStatus("/logs/log_1", 1, 1L, false),
            createFileStatus("/logs/log_2", 1, 2L, false),
            createFileStatus("/logs/log_3", 1, 92L, false),
            createFileStatus("/logs/log_4", 1, 93L, false),
            createFileStatus("/logs/log_5", 1, 3L, false)
        );

        Set<String> expectedPaths = Sets.newHashSet(
            "/logs/log_1",
            "/logs/log_2",
            "/logs/log_5"
        );

        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(logFiles);
        assertEquals(expectedPaths, extractRelativePaths(actualExpiredFiles));
    }

    @Test
    public void testGetStaleFilesFromEmptySet() {
        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(Collections.emptySet());

        assertTrue(actualExpiredFiles.isEmpty());
    }
}
