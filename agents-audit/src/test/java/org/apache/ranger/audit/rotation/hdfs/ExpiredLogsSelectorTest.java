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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.curator.shaded.com.google.common.collect.Sets;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.junit.Before;
import org.junit.Test;

public class ExpiredLogsSelectorTest extends BaseHdfsTest {

    private static final long CURRENT_TIME_MS = 100L;
    private static final long DEFAULT_RETENTION_PERIOD_MS = 50L;

    private ExpiredLogsSelector expiredLogsSelector;

    @Before
    public void initSelector() {
        expiredLogsSelector = new ExpiredLogsSelector(
            DEFAULT_RETENTION_PERIOD_MS,
            () -> CURRENT_TIME_MS
        );
    }

    @Test
    public void testGetStaleFiles() {
        Set<FileStatus> fileStatuses = buildTestFiles(6, 15L, 0L);
        Set<FileStatus> actualExpiredFiles = expiredLogsSelector.getStaleFiles(fileStatuses);

        Set<String> expectedFiles = Sets.newHashSet(
            "/logs/log_1",
            "/logs/log_2",
            "/logs/log_3"
        );

        assertEquals(expectedFiles, extractRelativePaths(actualExpiredFiles));
    }

    @Test
    public void testGetStaleFilesWithModificationTimeEqualToRetentionPeriod() {
        Set<FileStatus> fileStatuses = buildTestFiles(4, 25L, 0L);
        Set<FileStatus> actualExpiredFiles = expiredLogsSelector.getStaleFiles(fileStatuses);

        Set<String> expectedFiles = Sets.newHashSet(
            "/logs/log_1",
            "/logs/log_2"
        );

        assertEquals(expectedFiles, extractRelativePaths(actualExpiredFiles));
    }

    @Test
    public void testGetNoStaleFiles() {
        Set<FileStatus> fileStatuses = buildTestFiles(49, 1L, 50);
        Set<FileStatus> actualExpiredFiles = expiredLogsSelector.getStaleFiles(fileStatuses);

        assertTrue(actualExpiredFiles.isEmpty());
    }

    @Test
    public void testGetStaleFilesFromEmptySet() {
        Set<FileStatus> actualExpiredFiles = expiredLogsSelector.getStaleFiles(Collections.emptySet());

        assertTrue(actualExpiredFiles.isEmpty());
    }

    private Set<FileStatus> buildTestFiles(int count,
                                           long modificationTimeGap,
                                           long startModificationTime) {
        Set<FileStatus> fileStatuses = IntStream.range(1, count + 1)
            .mapToObj(i -> createFileStatus(
                "/logs/log_" + i,
                0,
                startModificationTime + i * modificationTimeGap,
                false))
            .collect(Collectors.toSet());

        fileStatuses.add(createFileStatus("/logs/expired_dir1", 0, 0, true));
        fileStatuses.add(createFileStatus("/logs/expired_dir2", 0, 10, true));

        return fileStatuses;
    }
}
