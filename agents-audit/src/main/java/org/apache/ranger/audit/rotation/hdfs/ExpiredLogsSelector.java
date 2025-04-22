package org.apache.ranger.audit.rotation.hdfs;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileStatus;

public class ExpiredLogsSelector implements StaleLogsSelector {

    private final long retentionMillis;
    private final Supplier<Long> currentTimeSupplier;

    public ExpiredLogsSelector(long retentionMillis) {
        this(retentionMillis, System::currentTimeMillis);
    }

    public ExpiredLogsSelector(long retentionMillis, Supplier<Long> currentTimeSupplier) {
        this.retentionMillis = retentionMillis;
        this.currentTimeSupplier = currentTimeSupplier;
    }

    @Override
    public Set<FileStatus> getStaleFiles(Set<FileStatus> statuses) {
        long now = currentTimeSupplier.get();
        return statuses.stream()
            .filter(status -> now - status.getModificationTime() >= retentionMillis)
            .collect(Collectors.toSet());
    }
}
