package org.apache.ranger.audit.rotation.hdfs;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.apache.commons.compress.utils.Sets;
import org.apache.curator.shaded.com.google.common.util.concurrent.MoreExecutors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.ranger.audit.provider.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HdfsStaleLogsManager implements StaleLogsManager, LogFilesFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsStaleLogsManager.class);

    private final StaleLogsSelector staleLogsSelector;
    private final FileSystem fileSystem;
    private final ExecutorService executorService;
    private final Supplier<Long> currentTimeSupplier;

    public HdfsStaleLogsManager(
        StaleLogsSelector staleLogsSelector,
        FileSystem fileSystem,
        ExecutorService executorService, Supplier<Long> currentTimeSupplier
    ) {
        this.staleLogsSelector = staleLogsSelector;
        this.fileSystem = fileSystem;
        this.executorService = executorService;
        this.currentTimeSupplier = currentTimeSupplier;
    }

    @Override
    public Set<FileStatus> listLogFiles(String logDirectoryTemplate) throws IOException {
        String logDirectory = MiscUtil.replaceTokens(
            logDirectoryTemplate,
            currentTimeSupplier.get());
        Path logDirectoryPath = new Path(logDirectory);
        return Sets.newHashSet(fileSystem.listStatus(logDirectoryPath));
    }

    @Override
    public void deleteStaleLogs(Set<FileStatus> logFiles) {
        executorService.submit(() -> execDeleteStaleLogs(logFiles));
    }

    private void execDeleteStaleLogs(Set<FileStatus> logFiles) {
        Set<FileStatus> staleFiles = staleLogsSelector.getStaleFiles(logFiles);

        MiscUtil.executePrivilegedAction(() -> deleteFiles(staleFiles));
    }

    private void deleteFiles(Set<FileStatus> files) {
        try {
            for (FileStatus fileStatus : files) {
                fileSystem.delete(fileStatus.getPath(), false);
            }
        } catch (IOException e) {
            LOG.warn("Error deleting stale audit logs {}", files, e);
        }
    }

    @Override
    public void close() throws Exception {
        executorService.shutdown();
    }

    public static HdfsStaleLogsManager create(
        StaleLogsSelector staleLogsSelector,
        String logDirectoryTemplate,
        Configuration conf,
        boolean isAsync) {
        try {
            String logDirectory = MiscUtil.replaceTokens(
                logDirectoryTemplate, System.currentTimeMillis());

            ExecutorService executorService = isAsync
                ? Executors.newSingleThreadExecutor()
                // run directly in the caller thread in case of sync stale logs removal
                : MoreExecutors.newDirectExecutorService();

            return new HdfsStaleLogsManager(
                staleLogsSelector,
                new Path(logDirectory).getFileSystem(conf),
                executorService,
                System::currentTimeMillis
            );
        } catch (IOException e) {
            throw new RuntimeException("Error creating HdfsStaleLogsManager", e);
        }

    }
}
