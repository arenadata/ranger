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

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.ranger.audit.destination.PaimonAuditDestination;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PaimonAuditCompactorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testCompactionReducesFilesAndPreservesRows() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse").toPath();
        String warehouseUri = "file://" + warehouse.toAbsolutePath();

        long rows = 0;
        for (int batch = 0; batch < 6; batch++) {
            rows += seedBatch(warehouseUri, batch);
        }

        long logicalBefore = countLogicalDataFiles(warehouseUri);
        assertTrue("expected several small data files before compaction", logicalBefore > 3);

        PaimonAuditCompactor compactor = new PaimonAuditCompactor(
                warehouseUri, "audit", "access_audit", null, null, null);
        compactor.compactOnce();

        // physical files linger until snapshot expiry; the compaction commit
        // is what must shrink the logical file set
        long logicalAfter = countLogicalDataFiles(warehouseUri);
        assertTrue("expected fewer logical data files after compaction, was "
                + logicalBefore + " -> " + logicalAfter, logicalAfter < logicalBefore);
        assertEquals(rows, countRows(warehouseUri));
    }

    @Test
    public void testOrphanCleanRemovesOnlyStaleUnreferencedFiles() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse-orphan").toPath();
        String warehouseUri = "file://" + warehouse.toAbsolutePath();
        seedBatch(warehouseUri, 0);

        Path tableDir = warehouse.resolve("audit.db/access_audit");
        Path bucketDir;
        try (Stream<Path> walk = Files.walk(tableDir)) {
            bucketDir = walk.filter(p -> p.getFileName().toString().startsWith("bucket-"))
                    .findFirst().orElseThrow(() -> new AssertionError("no bucket dir"));
        }
        Path staleOrphan = bucketDir.resolve("stale-orphan.bin");
        Files.write(staleOrphan, new byte[] {1});
        Files.setLastModifiedTime(staleOrphan,
                FileTime.fromMillis(System.currentTimeMillis() - 2 * 24 * 3600_000L));
        Path freshOrphan = bucketDir.resolve("fresh-orphan.bin");
        Files.write(freshOrphan, new byte[] {1});

        PaimonAuditCompactor compactor = new PaimonAuditCompactor(
                warehouseUri, "audit", "access_audit", null, null, null);
        compactor.cleanOrphans(24 * 3600_000L);

        assertFalse(Files.exists(staleOrphan));
        assertTrue(Files.exists(freshOrphan));
        assertTrue(countDataFiles(warehouse) > 0);
    }

    private long seedBatch(String warehouseUri, int batch) throws Exception {
        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", warehouseUri);
        props.setProperty(prefix + ".database", "audit");
        props.setProperty(prefix + ".table", "access_audit");
        props.setProperty(prefix + ".commit.interval.ms", "100");
        destination.init(props, prefix);

        long deadline = System.currentTimeMillis() + 30_000L;
        while (!destination.isReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        List<org.apache.ranger.audit.model.AuditEventBase> events = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            AuthzAuditEvent event = new AuthzAuditEvent();
            event.setEventId("evt-" + batch + "-" + i);
            event.setEventTime(new Date());
            event.setUser("user" + i);
            event.setResourcePath("/data/file-" + i);
            event.setAccessType("read");
            event.setSeqNum(batch * 1000L + i);
            events.add(event);
        }
        assertTrue(destination.log(events));
        destination.flush();
        destination.stop();
        return events.size();
    }

    private long countLogicalDataFiles(String warehouseUri) throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouseUri);
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
        try {
            org.apache.paimon.table.Table table =
                    catalog.getTable(Identifier.create("audit", "access_audit"));
            long files = 0;
            for (org.apache.paimon.table.source.Split split :
                    table.newReadBuilder().newScan().plan().splits()) {
                files += ((org.apache.paimon.table.source.DataSplit) split).dataFiles().size();
            }
            return files;
        } finally {
            catalog.close();
        }
    }

    private long countDataFiles(Path warehouse) throws Exception {
        try (Stream<Path> walk = Files.walk(warehouse)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".parquet")).count();
        }
    }

    private long countRows(String warehouseUri) throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouseUri);
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
        try {
            org.apache.paimon.table.Table table =
                    catalog.getTable(Identifier.create("audit", "access_audit"));
            final long[] count = {0};
            try (RecordReader<InternalRow> reader = table.newReadBuilder().newRead().createReader(
                    table.newReadBuilder().newScan().plan().splits())) {
                reader.forEachRemaining(row -> count[0]++);
            }
            return count[0];
        } finally {
            catalog.close();
        }
    }
}
