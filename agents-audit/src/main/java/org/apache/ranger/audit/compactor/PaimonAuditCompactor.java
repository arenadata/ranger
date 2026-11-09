/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.audit.compactor;

import org.apache.paimon.append.AppendCompactCoordinator;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.ranger.audit.paimon.PaimonAuditSchema;
import org.apache.paimon.operation.BaseAppendFileStoreWrite;
import org.apache.paimon.operation.CleanOrphanFilesResult;
import org.apache.paimon.operation.LocalOrphanFilesClean;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.TableCommitImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintenance pass for the Paimon audit table: merges small append files
 * (AppendCompactCoordinator) and removes orphan files left by dead writers.
 * Both operations are idempotent and safe to run concurrently from several
 * host services: commits are optimistic (CAS on snapshot), orphan deletion
 * is a retriable HDFS delete.
 */
public class PaimonAuditCompactor {
    private static final Logger LOG = LoggerFactory.getLogger(PaimonAuditCompactor.class);

    private final String warehouse;
    private final String database;
    private final String tableName;
    private final String catalogType;
    private final String catalogUri;

    public PaimonAuditCompactor(String warehouse, String database, String tableName,
                                String catalogType, String catalogUri) {
        this.warehouse = warehouse;
        this.database = database;
        this.tableName = tableName;
        this.catalogType = catalogType;
        this.catalogUri = catalogUri;
    }

    public int compactOnce() throws Exception {
        FileStoreTable table = openTable();
        String commitUser = "ranger-audit-compactor-" + java.util.UUID.randomUUID();
        BaseAppendFileStoreWrite write =
                (BaseAppendFileStoreWrite) table.store().newWrite(commitUser);
        int committed = 0;
        try (TableCommitImpl commit = table.newCommit(commitUser)) {
            List<org.apache.paimon.append.AppendCompactTask> tasks =
                    new AppendCompactCoordinator(table, false).run();
            if (tasks.isEmpty()) {
                return 0;
            }
            List<CommitMessage> messages = new ArrayList<>();
            for (org.apache.paimon.append.AppendCompactTask task : tasks) {
                messages.add(task.doCompact(table, write));
            }
            commit.commit(messages);
            committed = messages.size();
        } finally {
            write.close();
        }
        LOG.info("Compaction committed {} tasks for {}.{}", committed, database, tableName);
        return committed;
    }

    public long cleanOrphans(long olderThanDurationMillis) throws Exception {
        // OrphanFilesClean expects an absolute threshold timestamp
        long olderThan = System.currentTimeMillis() - olderThanDurationMillis;
        try (Catalog catalog = openCatalog()) {
            CleanOrphanFilesResult result =
                    LocalOrphanFilesClean.executeDatabaseOrphanFiles(
                            catalog, database, tableName, olderThan, null, false);
            LOG.info("Orphan clean for {}.{}: deleted {} files, {} bytes",
                    database, tableName, result.getDeletedFileCount(), result.getDeletedFileTotalLenInBytes());
            return result.getDeletedFileCount();
        }
    }

    private Catalog openCatalog() {
        return CatalogFactory.createCatalog(CatalogContext.create(
                PaimonAuditSchema.catalogOptions(warehouse, catalogType, catalogUri)));
    }

    private FileStoreTable openTable() throws Exception {
        try (Catalog catalog = openCatalog()) {
            return (FileStoreTable) catalog.getTable(Identifier.create(database, tableName));
        }
    }

    /**
     * Standalone entry point for timer-based deployments:
     * main &lt;warehouse&gt; &lt;database&gt; &lt;table&gt; [catalogType] [catalogUri] [olderThanMillis]
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: main <warehouse> <database> <table> [catalogType] [catalogUri] [olderThanMillis]");
            System.exit(1);
        }
        String catalogType = args.length > 3 ? args[3] : null;
        String catalogUri = args.length > 4 ? args[4] : null;
        long olderThanMillis = args.length > 5 ? Long.parseLong(args[5]) : 24 * 3600_000L;

        PaimonAuditCompactor compactor =
                new PaimonAuditCompactor(args[0], args[1], args[2], catalogType, catalogUri);
        int tasks = compactor.compactOnce();
        long orphans = compactor.cleanOrphans(olderThanMillis);
        System.out.println("compacted=" + tasks + " orphanDeleted=" + orphans);
    }
}
