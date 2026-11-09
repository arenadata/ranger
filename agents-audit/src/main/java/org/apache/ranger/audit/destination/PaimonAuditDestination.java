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

package org.apache.ranger.audit.destination;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.ranger.audit.model.AuditEventBase;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.audit.paimon.PaimonAuditSchema;
import org.apache.ranger.audit.provider.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes audit events to an append-only Paimon table (parquet + zstd, partitioned by
 * event day). Rows are buffered in memory and committed by a background thread at a
 * fixed interval, keeping the global commit rate independent of the event arrival
 * rate. If commits fail, log() reports failure so the upstream spool retains events.
 */
public class PaimonAuditDestination extends AuditDestination {
    private static final Logger LOG = LoggerFactory.getLogger(PaimonAuditDestination.class);

    public static final String PROP_WAREHOUSE = "warehouse";
    public static final String PROP_DATABASE = "database";
    public static final String PROP_TABLE = "table";
    public static final String PROP_CATALOG_TYPE = "catalog.type";
    public static final String PROP_CATALOG_URI = "catalog.uri";
    public static final String PROP_HADOOP_CONF_DIR = "hadoop.conf.dir";
    public static final String PROP_COMMIT_INTERVAL_MS = "commit.interval.ms";
    public static final String PROP_PARTITION_EXPIRATION = "partition.expiration";
    public static final String PROP_MAX_BUFFERED_EVENTS = "max.buffered.events";

    public static final long DEFAULT_COMMIT_INTERVAL_MS = 60_000L;
    public static final long MIN_COMMIT_INTERVAL_MS = 1_000L;
    public static final String DEFAULT_PARTITION_EXPIRATION =
            PaimonAuditSchema.DEFAULT_PARTITION_EXPIRATION;
    private static final long DEFAULT_MAX_BUFFERED_EVENTS = 200_000L;

    private final ArrayDeque<InternalRow> buffer = new ArrayDeque<>();
    private final AtomicLong commitIdentifier = new AtomicLong();

    private Table table;
    private StreamTableWrite writer;
    private StreamTableCommit committer;
    private Thread committerThread;
    private volatile boolean stopped;
    private volatile boolean initialized;
    private volatile boolean commitHealthy;
    private boolean disabled;
    private String warehouse;
    private String database = PaimonAuditSchema.DEFAULT_DATABASE;
    private String tableName = PaimonAuditSchema.DEFAULT_TABLE;
    private String partitionExpiration = PaimonAuditSchema.DEFAULT_PARTITION_EXPIRATION;
    private Options catalogOptions;
    private long commitIntervalMs = DEFAULT_COMMIT_INTERVAL_MS;
    private long maxBufferedEvents = DEFAULT_MAX_BUFFERED_EVENTS;

    // events arrive in near-time order; one dt string per day is enough
    private long lastEpochDay = Long.MIN_VALUE;
    private BinaryString lastDtValue;

    @Override
    public void init(Properties props, String propPrefix) {
        LOG.info("init() called");
        super.init(props, propPrefix);

        try {
            this.warehouse = getRequiredProperty(PROP_WAREHOUSE);
            String databaseProp = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_DATABASE);
            String tableNameProp = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_TABLE);
            String catalogType = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_CATALOG_TYPE);
            String catalogUri = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_CATALOG_URI);
            String hadoopConfDirProp = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_HADOOP_CONF_DIR);
            String interval = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_COMMIT_INTERVAL_MS);
            String partitionExpirationProp =
                    MiscUtil.getStringProperty(props, propPrefix + "." + PROP_PARTITION_EXPIRATION);
            String maxBufferedProp =
                    MiscUtil.getStringProperty(props, propPrefix + "." + PROP_MAX_BUFFERED_EVENTS);

            if (databaseProp != null && !databaseProp.trim().isEmpty()) {
                this.database = databaseProp.trim();
            }
            if (tableNameProp != null && !tableNameProp.trim().isEmpty()) {
                this.tableName = tableNameProp.trim();
            }
            if (interval != null && !interval.trim().isEmpty()) {
                this.commitIntervalMs = Math.max(Long.parseLong(interval.trim()), MIN_COMMIT_INTERVAL_MS);
            }
            if (partitionExpirationProp != null && !partitionExpirationProp.trim().isEmpty()) {
                this.partitionExpiration = partitionExpirationProp.trim();
            }
            if (maxBufferedProp != null && !maxBufferedProp.trim().isEmpty()) {
                this.maxBufferedEvents = Long.parseLong(maxBufferedProp.trim());
            }

            String hadoopConfDir = hadoopConfDirProp == null ? null : hadoopConfDirProp.trim();
            this.catalogOptions = PaimonAuditSchema.catalogOptions(warehouse, catalogType, catalogUri, hadoopConfDir);
        } catch (Throwable t) {
            // never fail host-service startup on a bad destination config: stay
            // degraded instead, log() keeps refusing events so the spool retains them
            disabled = true;
            logError("Paimon audit destination disabled due to invalid configuration", t);
            return;
        }

        // Catalog and table setup run in the background: this destination is
        // initialized inside the host service startup path (e.g. NameNode), and a
        // blocking warehouse RPC there can stall the whole service. Until ready,
        // log() reports failure and the upstream spool retains events.
        this.committerThread = new Thread(this::initializeAndCommitLoop, "paimon-audit-committer");
        this.committerThread.setDaemon(true);
        this.committerThread.start();
        LOG.info("Paimon audit destination init scheduled. Table=" + database + "." + tableName
                + ", warehouse=" + warehouse + ", commitIntervalMs=" + commitIntervalMs);
    }

    public boolean isReady() {
        return initialized && !stopped;
    }

    @Override
    public boolean log(Collection<AuditEventBase> events) {
        logStatusIfRequired();
        addTotalCount(events.size());

        if (disabled || !isReady() || !commitHealthy || buffer.size() >= maxBufferedEvents) {
            addDeferredCount(events.size());
            return false;
        }

        List<InternalRow> rows;
        try {
            rows = new ArrayList<>(events.size());
            for (AuditEventBase event : events) {
                rows.add(toRow((AuthzAuditEvent) event));
            }
        } catch (Throwable t) {
            // e.g. a corrupt event replayed from the spool; must never escape:
            // AuditBatchQueue runs log() outside its try/catch
            addDeferredCount(events.size());
            logError("Failed to map audit events to Paimon rows", t);
            return false;
        }

        synchronized (buffer) {
            buffer.addAll(rows);
        }
        return true;
    }

    @Override
    public void flush() {
        if (initialized && !stopped) {
            commitBuffered();
        }
    }

    @Override
    public void stop() {
        LOG.info("stop() called");
        stopped = true;
        if (committerThread != null) {
            committerThread.interrupt();
        }
        try {
            if (initialized) {
                commitBuffered();
            }
        } catch (Throwable t) {
            logError("Final commit failed", t);
        }
        closeWriter();
    }

    private void initializeAndCommitLoop() {
        while (!stopped && !initialized) {
            try {
                openCatalogAndWriter();
                if (stopped) {
                    closeWriter();
                    return;
                }
                initialized = true;
                commitHealthy = true;
                LOG.info("Paimon audit destination started. Table=" + database + "." + tableName);
            } catch (Throwable t) {
                commitHealthy = false;
                closeWriter();
                logError("Paimon audit destination initialization failed, will retry", t);
                try {
                    Thread.sleep(Math.max(commitIntervalMs, 10_000L));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        if (stopped) {
            return;
        }
        commitLoop();
    }

    private void openCatalogAndWriter() throws Exception {
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(catalogOptions));
        try {
            Identifier tableId = Identifier.create(database, tableName);
            this.table = openOrCreateTable(catalog, tableId, partitionExpiration);

            StreamWriteBuilder writeBuilder = table.newStreamWriteBuilder()
                    .withCommitUser("ranger-" + hostTag() + "-" + UUID.randomUUID());
            StreamTableWrite newWriter = writeBuilder.newWrite();
            try {
                this.committer = writeBuilder.newCommit();
            } catch (Throwable t) {
                try {
                    newWriter.close();
                } catch (Throwable ignore) {
                    // best effort cleanup
                }
                throw t;
            }
            this.writer = newWriter;
        } finally {
            catalog.close();
        }
    }

    private void commitLoop() {
        while (!stopped) {
            try {
                Thread.sleep(commitIntervalMs);
            } catch (InterruptedException e) {
                break;
            }
            try {
                commitBuffered();
            } catch (Throwable t) {
                logError("Periodic commit failed", t);
            }
        }
    }

    private void commitBuffered() {
        List<InternalRow> batch = new ArrayList<>();
        synchronized (buffer) {
            batch.addAll(buffer);
            buffer.clear();
        }
        if (batch.isEmpty()) {
            return;
        }

        synchronized (this) {
            if (writer == null || committer == null) {
                // closed by stop() while rows were in flight; nobody will commit
                // them here, put them back for the caller to handle
                requeue(batch);
                return;
            }
            try {
                for (InternalRow row : batch) {
                    writer.write(row);
                }
                List<CommitMessage> messages = writer.prepareCommit(false, commitIdentifier.incrementAndGet());
                committer.commit(commitIdentifier.get(), messages);
                addSuccessCount(batch.size());
                commitHealthy = true;
            } catch (Throwable t) {
                // return rows to the buffer for the next retry
                requeue(batch);
                commitHealthy = false;
                addFailedCount(batch.size());
                throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
            }
        }
    }

    private void requeue(List<InternalRow> batch) {
        synchronized (buffer) {
            for (int i = batch.size() - 1; i >= 0; i--) {
                buffer.addFirst(batch.get(i));
            }
        }
    }

    private Table openOrCreateTable(Catalog catalog, Identifier tableId, String partitionExpiration)
            throws Exception {
        try {
            return catalog.getTable(tableId);
        } catch (Catalog.TableNotExistException e) {
            LOG.info("Paimon audit table " + tableId + " does not exist, creating it");
            try {
                catalog.createDatabase(tableId.getDatabaseName(), false);
            } catch (Catalog.DatabaseAlreadyExistException ignore) {
                // concurrent creation
            }
            Map<String, String> tableOptions = new HashMap<>();
            tableOptions.put("file.format", "parquet");
            tableOptions.put("file.compression", "zstd");
            tableOptions.put("bucket", "-1");
            tableOptions.put("snapshot.num-retained.min", "10");
            tableOptions.put("snapshot.num-retained.max", "20");
            tableOptions.put("partition.expiration-time", partitionExpiration);
            tableOptions.put("partition.timestamp-formatter", "yyyy-MM-dd");

            Schema.Builder builder = new Schema.Builder();
            for (int i = 0; i < PaimonAuditSchema.COLUMN_NAMES.length; i++) {
                builder.column(PaimonAuditSchema.COLUMN_NAMES[i], PaimonAuditSchema.COLUMN_TYPES[i]);
            }
            builder.partitionKeys(java.util.Collections.singletonList(PaimonAuditSchema.COLUMN_NAMES[PaimonAuditSchema.IDX_DT]));
            builder.options(tableOptions);

            try {
                catalog.createTable(tableId, builder.build(), false);
            } catch (Catalog.TableAlreadyExistException ignore) {
                // concurrent creation
            }
            return catalog.getTable(tableId);
        }
    }

    private void closeWriter() {
        synchronized (this) {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (Throwable t) {
                LOG.error("Error closing Paimon writer", t);
            } finally {
                writer = null;
            }
            try {
                if (committer != null) {
                    committer.close();
                }
            } catch (Throwable t) {
                LOG.error("Error closing Paimon committer", t);
            } finally {
                committer = null;
            }
        }
    }

    private String getRequiredProperty(String name) {
        String value = MiscUtil.getStringProperty(props, propPrefix + "." + name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Property " + propPrefix + "." + name + " is required");
        }
        return value.trim();
    }

    private static String hostTag() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host.replaceAll("[^A-Za-z0-9._-]", "_");
        } catch (Throwable t) {
            return "unknown-host";
        }
    }

    private InternalRow toRow(AuthzAuditEvent event) {
        long eventMillis = event.getEventTime() == null
                ? System.currentTimeMillis() : event.getEventTime().getTime();
        long epochDay = Math.floorDiv(eventMillis, 86_400_000L);
        if (epochDay != lastEpochDay) {
            lastEpochDay = epochDay;
            lastDtValue = BinaryString.fromString(PaimonAuditSchema.dayString(eventMillis));
        }
        GenericRow row = (GenericRow) PaimonAuditSchema.toRow(event);
        row.setField(PaimonAuditSchema.IDX_DT, lastDtValue);
        return row;
    }
}
