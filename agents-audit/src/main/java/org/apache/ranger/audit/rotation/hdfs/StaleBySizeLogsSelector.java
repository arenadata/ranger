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

        while (logsSizeAfterDeletion >= retentionSizeBytes) {
            FileStatus fileStatus = fileQueue.poll();
            if (fileStatus == null) {
                break;
            }

            filesToDelete.add(fileStatus);
            logsSizeAfterDeletion -= fileStatus.getLen();
        }

        return filesToDelete;
    }
}
