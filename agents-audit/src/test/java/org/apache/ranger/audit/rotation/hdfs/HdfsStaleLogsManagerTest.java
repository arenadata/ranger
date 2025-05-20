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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.MoreExecutors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HdfsStaleLogsManagerTest extends BaseHdfsTest {

    private HdfsStaleLogsManager staleLogsManager;

    @Before
    public void initLogsManager() {
        staleLogsManager = new HdfsStaleLogsManager(
            new MockStaleLogsSelector(),
            fileSystem,
            MoreExecutors.newDirectExecutorService(),
            System::currentTimeMillis
        );
    }

    @After
    public void closeLogsManager() {
        staleLogsManager.close();
    }

    @Test
    public void testListLogFiles() throws Exception {
        Set<Path> logFiles = Stream.of("log1", "log2", "log3")
            .map(file -> fileSystem.makeQualified(new Path("/logs", file)))
            .collect(Collectors.toSet());

        logFiles.forEach(this::createTestFile);

        createTestFile(new Path("/another_dir/file1"));
        createTestFile(new Path("/another_dir/file2"));
        createTestFile(new Path("/third_dir/file2"));

        Set<FileStatus> fileStatuses = staleLogsManager.listLogFiles("/logs");
        Set<Path> actualLogs = getPaths(fileStatuses);
        assertEquals(logFiles, actualLogs);
    }

    @Test
    public void testListLogFilesInEmptyDir() throws Exception {
        fileSystem.mkdirs(new Path("/empty_dir"));

        Set<FileStatus> emptyDirFileStatuses = staleLogsManager.listLogFiles("/empty_dir");
        assertTrue(emptyDirFileStatuses.isEmpty());
    }

    @Test
    public void testListLogFilesInNonExistentDir() {
        assertThrows(IOException.class,
            () -> staleLogsManager.listLogFiles("/non_existent_dir"));
    }

    @Test
    public void testDeleteStaleLogs() throws Exception {
        Stream.of(
                "log1",
                "log2",
                "log3_expired",
                "log4_expired",
                "log5",
                "log6_expired"
            ).map(file -> new Path("/logs", file))
            .forEach(this::createTestFile);

        Set<FileStatus> fileStatuses = staleLogsManager.listLogFiles("/logs");
        staleLogsManager.deleteStaleLogs(fileStatuses);

        assertFalse(fileSystem.exists(new Path("/logs/log3_expired")));
        assertFalse(fileSystem.exists(new Path("/logs/log4_expired")));
        assertFalse(fileSystem.exists(new Path("/logs/log6_expired")));

        assertTrue(fileSystem.exists(new Path("/logs/log1")));
        assertTrue(fileSystem.exists(new Path("/logs/log2")));
        assertTrue(fileSystem.exists(new Path("/logs/log5")));
    }

    private static class MockStaleLogsSelector implements StaleLogsSelector {

        @Override
        public Set<FileStatus> getStaleFiles(Set<FileStatus> statuses) {
            return statuses.stream()
                .filter(status -> status.getPath().toString().contains("expired"))
                .collect(Collectors.toSet());
        }
    }
}
