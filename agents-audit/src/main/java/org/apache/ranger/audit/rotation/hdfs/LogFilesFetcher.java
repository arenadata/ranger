package org.apache.ranger.audit.rotation.hdfs;

import java.io.IOException;
import java.util.Set;
import org.apache.hadoop.fs.FileStatus;

public interface LogFilesFetcher {
    Set<FileStatus> listLogFiles(String logDirectoryTemplate) throws IOException;
}
