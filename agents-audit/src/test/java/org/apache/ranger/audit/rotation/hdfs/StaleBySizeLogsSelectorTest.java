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

public class StaleBySizeLogsSelectorTest extends BaseHdfsTest {

    private static final long DEFAULT_RETENTION_SIZE_BYTES = 50L;

    private StaleBySizeLogsSelector staleLogsSelector;

    @Before
    public void initSelector() {
        staleLogsSelector = new StaleBySizeLogsSelector(DEFAULT_RETENTION_SIZE_BYTES);
    }

    @Test
    public void testGetStaleFilesIfThresholdNotReached() {
        Set<FileStatus> logFiles = createTestFiles(2, 5);
        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(logFiles);

        assertTrue(actualExpiredFiles.isEmpty());
    }

    @Test
    public void testGetStaleFilesIfThresholdReached() {
        Set<FileStatus> logFiles = createTestFiles(7, 11);
        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(logFiles);

        Set<String> actualPaths = extractRelativePaths(actualExpiredFiles);

        Set<String> expectedPaths = Sets.newHashSet(
            "/logs/log_4",
            "/logs/log_5",
            "/logs/log_6"
        );

        assertEquals(expectedPaths, actualPaths);
    }

    @Test
    public void testGetStaleFilesIfDirSizeEqualToThreshold() {
        Set<FileStatus> logFiles = createTestFiles(5, 10);
        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(logFiles);

        Set<String> actualPaths = extractRelativePaths(actualExpiredFiles);

        Set<String> expectedPaths = Sets.newHashSet(
            "/logs/log_4"
        );

        assertEquals(expectedPaths, actualPaths);
    }

    @Test
    public void testGetStaleFilesFromEmptySet() {
        Set<FileStatus> actualExpiredFiles = staleLogsSelector.getStaleFiles(Collections.emptySet());

        assertTrue(actualExpiredFiles.isEmpty());
    }

    private Set<FileStatus> createTestFiles(int count, int fileSize) {
        Set<FileStatus> logFiles = IntStream.range(0, count)
            .mapToObj(i -> createFileStatus("/logs/log_" + i, fileSize, i, false))
            .collect(Collectors.toSet());

        logFiles.add(createFileStatus("/logs/some_dir1", 0, 4, true));
        logFiles.add(createFileStatus("/logs/some_dir2", 10, 5, true));

        return logFiles;
    }
}
