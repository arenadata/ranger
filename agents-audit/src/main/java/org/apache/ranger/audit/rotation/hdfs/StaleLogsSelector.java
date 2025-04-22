package org.apache.ranger.audit.rotation.hdfs;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import org.apache.hadoop.fs.FileStatus;

public interface StaleLogsSelector {
    Set<FileStatus> getStaleFiles(Set<FileStatus> statuses);

    static Optional<StaleLogsSelector> composite(long retentionMs, long retentionBytes) {
        ArrayList<StaleLogsSelector> staleLogsSelectors = new ArrayList<>();
        if (retentionMs > 0) {
            staleLogsSelectors.add(new ExpiredLogsSelector(retentionBytes));
        }
        if (retentionBytes > 0) {
            staleLogsSelectors.add(new StaleBySizeLogsSelector(retentionBytes));
        }

        return staleLogsSelectors.isEmpty()
            ? Optional.empty()
            : Optional.of(new CompositeStaleLogsSelector(staleLogsSelectors));
    }
}
