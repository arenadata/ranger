package org.apache.ranger.audit.rotation.hdfs;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.fs.FileStatus;

public class CompositeStaleLogsSelector implements StaleLogsSelector {
    private final List<StaleLogsSelector> selectors;

    public CompositeStaleLogsSelector(List<StaleLogsSelector> selectors) {
        this.selectors = selectors;
    }

    @Override
    public Set<FileStatus> getStaleFiles(Set<FileStatus> statuses) {
        Set<FileStatus> remainingFiles = new HashSet<>(statuses);
        Set<FileStatus> staleFiles = new HashSet<>();

        for (StaleLogsSelector selector : selectors) {
            if (remainingFiles.isEmpty()) {
                break;
            }

            Set<FileStatus> selectorResult = selector.getStaleFiles(remainingFiles);
            staleFiles.addAll(selectorResult);
            remainingFiles.removeAll(selectorResult);
        }
        return staleFiles;
    }
}
