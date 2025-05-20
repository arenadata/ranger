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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileStatus;

public class StaleBySizeLogsSelector implements StaleLogsSelector {

    private final long retentionSizeBytes;

    public StaleBySizeLogsSelector(long retentionSizeBytes) {
        this.retentionSizeBytes = retentionSizeBytes;
    }

    @Override
    public Set<FileStatus> getStaleFiles(Set<FileStatus> statuses) {
        long totalLogsSize = statuses.stream()
            .filter(FileStatus::isFile)
            .map(FileStatus::getLen)
            .reduce(0L, Long::sum);

        return totalLogsSize < retentionSizeBytes
            ? Collections.emptySet()
            : getOldestFilesToDelete(statuses, totalLogsSize);
    }

    private Set<FileStatus> getOldestFilesToDelete(Set<FileStatus> statuses, long totalLogsSize) {
        Queue<FileStatus> fileQueue = statuses.stream()
            .sorted(Comparator.comparingLong(FileStatus::getModificationTime).reversed())
            .collect(Collectors.toCollection(ArrayDeque::new));

        Set<FileStatus> filesToDelete = new HashSet<>();
        long logsSizeAfterDeletion = totalLogsSize;

        while (!fileQueue.isEmpty() && logsSizeAfterDeletion >= retentionSizeBytes) {
            FileStatus fileStatus = fileQueue.poll();
            if (fileStatus.isDirectory()) {
                continue;
            }

            filesToDelete.add(fileStatus);
            logsSizeAfterDeletion -= fileStatus.getLen();
        }

        return filesToDelete;
    }
}
