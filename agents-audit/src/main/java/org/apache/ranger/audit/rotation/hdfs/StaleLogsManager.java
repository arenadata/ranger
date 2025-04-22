package org.apache.ranger.audit.rotation.hdfs;

import java.util.Set;
import org.apache.hadoop.fs.FileStatus;

public interface StaleLogsManager extends AutoCloseable {
    void deleteStaleLogs(Set<FileStatus> logFiles);
}
