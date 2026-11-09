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
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.audit.paimon.PaimonAuditSchema;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PaimonAuditDestinationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testLifecycleWritesReadableRows() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse").toPath();
        Date eventTime = utcDate(2026, 11, 8, 10, 20, 30);
        List<org.apache.ranger.audit.model.AuditEventBase> events = Arrays.asList(
                createFullEvent(eventTime),
                createFullEvent(new Date(eventTime.getTime() + 1000L)),
                createFullEvent(new Date(eventTime.getTime() + 2000L)));

        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", "file://" + warehouse.toAbsolutePath());
        props.setProperty(prefix + ".database", "audit");
        props.setProperty(prefix + ".table", "access_audit");
        props.setProperty(prefix + ".commit.interval.ms", "200");
        destination.init(props, prefix);

        try {
            awaitReady(destination, 30_000L);
            assertTrue(destination.log(events));
            destination.flush();

            long rows = countRows("file://" + warehouse.toAbsolutePath(), "audit", "access_audit");
            assertEquals(3, rows);
        } finally {
            destination.stop();
        }
    }

    @Test
    public void testStopCommitsBufferedEventsWithoutFlush() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse-stop").toPath();
        Date eventTime = utcDate(2026, 11, 8, 10, 20, 30);
        List<org.apache.ranger.audit.model.AuditEventBase> events = Arrays.asList(
                createFullEvent(eventTime),
                createFullEvent(new Date(eventTime.getTime() + 1000L)));

        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", "file://" + warehouse.toAbsolutePath());
        props.setProperty(prefix + ".commit.interval.ms", "60000"); // committer never fires in this test
        destination.init(props, prefix);

        try {
            awaitReady(destination, 30_000L);
            assertTrue(destination.log(events));
            // no flush(): the JVM shutdown path must commit via stop()
        } finally {
            destination.stop();
        }

        assertEquals(2, countRows("file://" + warehouse.toAbsolutePath(), "audit", "access_audit"));
    }

    @Test
    public void testInitReturnsFastWhenWarehouseIsBroken() throws Exception {
        // a regular file occupies the warehouse path: catalog creation must fail,
        // but init() itself must not throw and must not block the caller
        java.io.File blocker = tempFolder.newFile("blocker");
        String warehouse = "file://" + blocker.toPath().getParent().toAbsolutePath() + "/blocker/warehouse";

        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", warehouse);
        props.setProperty(prefix + ".commit.interval.ms", "100");

        long t0 = System.currentTimeMillis();
        destination.init(props, prefix);
        long initMs = System.currentTimeMillis() - t0;

        try {
            assertTrue("init took too long: " + initMs + "ms", initMs < 5000);
            // until the writer is ready (never, in this test), events must not be accepted
            assertTrue(!destination.log(Arrays.asList(createFullEvent(utcDate(2026, 11, 8, 10, 0, 0)))));
        } finally {
            destination.stop();
        }
    }

    @Test
    public void testInitDoesNotThrowOnMissingWarehouse() {
        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        props.setProperty("xasecure.audit.destination.paimon.commit.interval.ms", "100");

        destination.init(props, "xasecure.audit.destination.paimon");

        try {
            // degraded, not dead: host service startup must survive a bad config
            assertTrue(!destination.log(Arrays.asList(createFullEvent(utcDate(2026, 11, 8, 10, 0, 0)))));
        } finally {
            destination.stop();
        }
    }

    @Test
    public void testLogReturnsFalseOnCorruptEventInsteadOfThrowing() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse-corrupt").toPath();
        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", "file://" + warehouse.toAbsolutePath());
        props.setProperty(prefix + ".commit.interval.ms", "100");
        destination.init(props, prefix);

        try {
            awaitReady(destination, 30_000L);
            List<org.apache.ranger.audit.model.AuditEventBase> corrupt =
                    Arrays.asList(createFullEvent(utcDate(2026, 11, 8, 10, 0, 0)), null);
            // a corrupt spool-replayed event must not escape as an exception
            assertTrue(!destination.log(corrupt));
        } finally {
            destination.stop();
        }
    }

    @Test
    public void testFlushAfterStopDoesNotThrow() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse-flush").toPath();
        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", "file://" + warehouse.toAbsolutePath());
        props.setProperty(prefix + ".commit.interval.ms", "100");
        destination.init(props, prefix);

        awaitReady(destination, 30_000L);
        destination.stop();
        destination.flush(); // shutdown chain may call flush after stop
    }

    @Test
    public void testFailureAndRecoveryPreservesEvents() throws Exception {
        Path warehouse = tempFolder.newFolder("warehouse-recovery").toPath();
        PaimonAuditDestination destination = new PaimonAuditDestination();
        Properties props = new Properties();
        String prefix = "xasecure.audit.destination.paimon";
        props.setProperty(prefix + ".warehouse", "file://" + warehouse.toAbsolutePath());
        props.setProperty(prefix + ".commit.interval.ms", "200");
        destination.init(props, prefix);

        awaitReady(destination, 30_000L);
        assertTrue(destination.log(Arrays.asList(
                createFullEvent(utcDate(2026, 11, 8, 10, 0, 0)),
                createFullEvent(utcDate(2026, 11, 8, 10, 0, 1)))));

        // break the warehouse: make every directory read-only so the next commit fails
        setWritableAllDirs(warehouse, false);
        boolean degraded = false;
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!destination.log(Arrays.asList(createFullEvent(utcDate(2026, 11, 8, 10, 0, 2))))) {
                degraded = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue("destination never degraded after warehouse failure", degraded);

        // heal the warehouse: buffered events must still land, then intake resumes
        setWritableAllDirs(warehouse, true);
        deadline = System.currentTimeMillis() + 30_000L;
        boolean recovered = false;
        while (System.currentTimeMillis() < deadline) {
            if (destination.log(Arrays.asList(createFullEvent(utcDate(2026, 11, 8, 10, 0, 3))))) {
                recovered = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue("destination never recovered after warehouse heal", recovered);
        deadline = System.currentTimeMillis() + 30_000L;
        long rows = 0;
        while (System.currentTimeMillis() < deadline) {
            rows = countRows("file://" + warehouse.toAbsolutePath(), "audit", "access_audit");
            if (rows >= 3) {
                break;
            }
            Thread.sleep(200);
        }
        // every accepted event must survive the failure window: the 2 initial ones,
        // at least the one accepted after recovery (poll events before degradation add more)
        assertTrue("expected at least 3 rows, got " + rows, rows >= 3);
        destination.stop();
    }

    private static void setWritableAllDirs(Path warehouse, boolean writable) throws Exception {
        java.nio.file.Files.walk(warehouse)
                .filter(java.nio.file.Files::isDirectory)
                .forEach(dir -> assertTrue(dir.toFile().setWritable(writable, true)));
    }

    private static void awaitReady(PaimonAuditDestination destination, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!destination.isReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    private long countRows(String warehouse, String database, String table) throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse);
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
        try {
            Table t = catalog.getTable(Identifier.create(database, table));
            final long[] count = {0};
            try (RecordReader<InternalRow> reader =
                         t.newReadBuilder().newRead().createReader(
                                 t.newReadBuilder().newScan().plan().splits())) {
                reader.forEachRemaining(row -> count[0]++);
            }
            return count[0];
        } finally {
            catalog.close();
        }
    }

    private AuthzAuditEvent createFullEvent(Date eventTime) {
        AuthzAuditEvent event = new AuthzAuditEvent();
        event.setEventId("evt-1");
        event.setEventTime(eventTime);
        event.setAccessType("select");
        event.setAclEnforcer("ranger-acl");
        event.setAgentId("agent-1");
        event.setRepositoryName("adh_hive");
        event.setSessionId("sess-42");
        event.setUser("alice");
        event.setRequestData("SELECT * FROM t");
        event.setResourcePath("/data/table/part-1");
        event.setResourceType("path");
        event.setClientIP("10.0.0.1");
        event.setLogType("RangerAudit");
        event.setAccessResult((short) 1);
        event.setPolicyId(7L);
        event.setRepositoryType(3);
        event.setResultReason("allowed by policy 7");
        event.setAction("query");
        event.setSeqNum(1234L);
        event.setEventCount(1L);
        event.setEventDurationMS(250L);
        event.setTags(new HashSet<>(Arrays.asList("PII", "SENSITIVE")));
        event.setClusterName("adh-cluster");
        event.setZoneName("zone-1");
        event.setAgentHostname("nn-1.host");
        event.setPolicyVersion(11L);
        return event;
    }

    private Date utcDate(int year, int month, int day, int hour, int minute, int second) {
        GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.set(year, month - 1, day, hour, minute, second);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    @Test
    public void testToRowMapsAllFields() {
        Date eventTime = utcDate(2026, 11, 8, 10, 20, 30);
        AuthzAuditEvent event = createFullEvent(eventTime);

        InternalRow row = PaimonAuditSchema.toRow(event);

        assertEquals("2026-11-08", row.getString(0).toString());
        assertEquals(eventTime.getTime(), row.getTimestamp(1, 3).getMillisecond());
        assertEquals("evt-1", row.getString(2).toString());
        assertEquals("select", row.getString(3).toString());
        assertEquals("ranger-acl", row.getString(4).toString());
        assertEquals("agent-1", row.getString(5).toString());
        assertEquals("adh_hive", row.getString(6).toString());
        assertEquals("sess-42", row.getString(7).toString());
        assertEquals("alice", row.getString(8).toString());
        assertEquals("SELECT * FROM t", row.getString(9).toString());
        assertEquals("/data/table/part-1", row.getString(10).toString());
        assertEquals("10.0.0.1", row.getString(11).toString());
        assertEquals("path", row.getString(12).toString());
        assertEquals("allowed by policy 7", row.getString(13).toString());
        assertEquals("query", row.getString(14).toString());
        assertEquals("PII,SENSITIVE", sortTags(row.getString(15).toString()));
        assertEquals("adh-cluster", row.getString(16).toString());
        assertEquals("zone-1", row.getString(17).toString());
        assertEquals("nn-1.host", row.getString(18).toString());
        assertEquals("RangerAudit", row.getString(19).toString());
        assertEquals(1, row.getInt(20));
        assertEquals(7L, row.getLong(21));
        assertEquals(3, row.getInt(22));
        assertEquals(1234L, row.getLong(23));
        assertEquals(1L, row.getLong(24));
        assertEquals(250L, row.getLong(25));
        assertEquals(11L, row.getLong(26));
    }

    @Test
    public void testToRowHandlesNulls() {
        AuthzAuditEvent event = new AuthzAuditEvent();

        InternalRow row = PaimonAuditSchema.toRow(event);

        assertTrue(row.isNullAt(2));  // id
        assertTrue(row.isNullAt(15)); // tags
        assertTrue(row.isNullAt(17)); // zoneName
        assertTrue(row.isNullAt(26)); // policyVersion
    }

    @Test
    public void testPartitionDayDerivedInUtc() {
        InternalRow beforeMidnight = PaimonAuditSchema.toRow(createFullEvent(utcDate(2026, 11, 8, 23, 59, 59)));
        InternalRow afterMidnight = PaimonAuditSchema.toRow(createFullEvent(utcDate(2026, 11, 9, 0, 0, 1)));

        assertEquals("2026-11-08", beforeMidnight.getString(0).toString());
        assertEquals("2026-11-09", afterMidnight.getString(0).toString());
    }

    private String sortTags(String tags) {
        String[] parts = tags.split(",");
        Arrays.sort(parts);
        return String.join(",", parts);
    }
}
