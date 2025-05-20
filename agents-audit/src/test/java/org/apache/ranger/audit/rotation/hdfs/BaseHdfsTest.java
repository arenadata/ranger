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

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.Before;
import org.junit.jupiter.api.Assertions;

public abstract class BaseHdfsTest {
    protected MiniDFSCluster hdfsCluster;
    protected FileSystem fileSystem;

    @Before
    public void setup() throws Exception {
        hdfsCluster = new MiniDFSCluster.Builder(new Configuration())
            .numDataNodes(1)
            .build();
        fileSystem = hdfsCluster.getFileSystem();
    }

    @After
    public void shutdown() throws Exception {
        hdfsCluster.shutdown(true);
    }

    protected Set<Path> getPaths(Set<FileStatus> fileStatuses) {
        return fileStatuses.stream()
            .map(FileStatus::getPath)
            .collect(Collectors.toSet());
    }

    protected Set<String> extractRelativePaths(Set<FileStatus> fileStatuses) {
        return fileStatuses.stream()
            .map(FileStatus::getPath)
            .map(Path::toUri)
            .map(URI::getPath)
            .collect(Collectors.toSet());
    }


    protected void createTestFile(Path path) {
        try {
            DFSTestUtil.writeFile(
                hdfsCluster.getFileSystem(),
                path,
                "test_content"
            );
        } catch (IOException e) {
            Assertions.fail(e);
        }
    }

    protected FileStatus createFileStatus(String path,
                                          int size,
                                          long modificationTime,
                                          boolean isDirectory) {
        return new FileStatus(
            size,
            isDirectory,
            1,
            100,
            modificationTime,
            new Path(path)
        );
    }
}
