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

package org.apache.ranger.paimon;

import org.apache.ranger.audit.compactor.PaimonAuditCompactor;
import org.apache.ranger.audit.paimon.PaimonAuditSchema;
import org.apache.ranger.common.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * Periodic maintenance of the Paimon audit table (small-file compaction and
 * orphan cleanup), runs inside Ranger Admin. Concurrent runs from several
 * admin instances are safe: compaction commits are optimistic, orphan
 * deletion is a retriable HDFS delete.
 */
// Lazy(false): the admin context is default-lazy-init and nothing references
// this bean — without it the maintenance would never start
@Lazy(false)
@Service
public class PaimonAuditMaintenance {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaimonAuditMaintenance.class);

    public static final String PROP_ENABLED = "ranger.paimon.audit.compaction.enabled";
    public static final String PROP_INTERVAL_SEC = "ranger.paimon.audit.compaction.interval.seconds";
    public static final String PROP_ORPHAN_OLDER_HOURS = "ranger.paimon.audit.orphan.older.than.hours";

    private static final long DEFAULT_INTERVAL_SEC = 600;
    private static final long DEFAULT_ORPHAN_OLDER_HOURS = 24;

    private Runnable task;
    private boolean enabled;
    private long intervalMs;

    private volatile Thread worker;

    public PaimonAuditMaintenance() {
        this.enabled = PropertiesUtil.getBooleanProperty(PROP_ENABLED, false);
        this.intervalMs = 1000L * PropertiesUtil.getLongProperty(PROP_INTERVAL_SEC, DEFAULT_INTERVAL_SEC);
        long orphanOlderMs = 3600_000L
                * PropertiesUtil.getLongProperty(PROP_ORPHAN_OLDER_HOURS, DEFAULT_ORPHAN_OLDER_HOURS);
        this.task = () -> runMaintenance(orphanOlderMs);
    }

    void configureForTest(Runnable task, boolean enabled, long intervalMs) {
        this.task = task;
        this.enabled = enabled;
        this.intervalMs = Math.max(intervalMs, 1_000L);
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("Paimon audit maintenance disabled ({})", PROP_ENABLED);
            return;
        }
        worker = new Thread(this::maintenanceLoop, "paimon-audit-maintenance");
        worker.setDaemon(true);
        worker.start();
        LOGGER.info("Paimon audit maintenance started, intervalMs={}", intervalMs);
    }

    @PreDestroy
    public void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    boolean isRunning() {
        Thread thread = worker;
        return thread != null && thread.isAlive();
    }

    private void maintenanceLoop() {
        while (worker != null) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.warn("Paimon audit maintenance pass failed", t);
            }
        }
    }

    private void runMaintenance(long orphanOlderMs) {
        String warehouse = PropertiesUtil.getProperty(PaimonAccessAuditsService.PROP_WAREHOUSE);
        if (warehouse == null || warehouse.isEmpty()) {
            return;
        }
        PaimonAuditCompactor compactor = new PaimonAuditCompactor(
                warehouse,
                PropertiesUtil.getProperty(PaimonAccessAuditsService.PROP_DATABASE,
                        PaimonAuditSchema.DEFAULT_DATABASE),
                PropertiesUtil.getProperty(PaimonAccessAuditsService.PROP_TABLE,
                        PaimonAuditSchema.DEFAULT_TABLE),
                PropertiesUtil.getProperty(PaimonAccessAuditsService.PROP_CATALOG_TYPE),
                PropertiesUtil.getProperty(PaimonAccessAuditsService.PROP_CATALOG_URI));
        int tasks;
        long orphans;
        try {
            tasks = compactor.compactOnce();
            orphans = compactor.cleanOrphans(orphanOlderMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            LOGGER.warn("Paimon audit maintenance pass failed", e);
            return;
        }
        if (tasks > 0 || orphans > 0) {
            LOGGER.info("Paimon audit maintenance: compacted {} tasks, deleted {} orphans", tasks, orphans);
        }
    }
}
