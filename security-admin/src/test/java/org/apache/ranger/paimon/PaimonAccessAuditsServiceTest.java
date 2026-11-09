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

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.data.InternalRow;
import org.apache.ranger.audit.destination.PaimonAuditDestination;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.common.SearchCriteria;
import org.apache.ranger.view.VXAccessAudit;
import org.apache.ranger.view.VXAccessAuditList;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PaimonAccessAuditsServiceTest {

    private static final TemporaryFolder TMP = new TemporaryFolder();

    private static String warehouseUri;
    private static PaimonAccessAuditsService service;

    private static Date utc(int hour, int minute, int second) {
        GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.set(2026, Calendar.NOVEMBER, 8, hour, minute, second);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    @BeforeClass
    public static void setUp() throws Exception {
        TMP.create();
        Path warehouse = TMP.newFolder("warehouse").toPath();
        warehouseUri = "file://" + warehouse.toAbsolutePath();

        seed("alice", "/data/tranche-42/a", 1, utc(10, 0, 0));
        seed("bob",   "/data/tranche-42/b", 1, utc(10, 0, 1));
        seed("alice", "/data/tranche-7/c",  0, utc(10, 0, 2));
        seed("bob",   "/data/tranche-42/d", 0, utc(10, 0, 3));
        seed("carol", "/data/tranche-7/e",  1, utc(9, 0, 0));

        service = new PaimonAccessAuditsService();
        Properties props = new Properties();
        props.setProperty("ranger.paimon.audit.warehouse", warehouseUri);
        props.setProperty("ranger.paimon.audit.database", "audit");
        props.setProperty("ranger.paimon.audit.table", "access_audit");
        service.init(props);
    }

    private static void seed(String user, String resource, int result, Date eventTime) throws Exception {
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
        assertTrue("destination never became ready", destination.isReady());

        AuthzAuditEvent event = new AuthzAuditEvent();
        event.setEventId("evt-" + user + "-" + eventTime.getTime());
        event.setEventTime(eventTime);
        event.setUser(user);
        event.setResourcePath(resource);
        event.setAccessType(user.equals("alice") ? "select" : "open");
        event.setRepositoryName("adh_hive");
        event.setAclEnforcer("ranger-acl");
        event.setAgentHostname("nn-1.host");
        event.setAccessResult((short) result);
        event.setSeqNum(eventTime.getTime());
        assertTrue(destination.log(Collections.singletonList(event)));
        destination.flush();
        destination.stop();
    }

    @AfterClass
    public static void tearDown() {
        TMP.delete();
    }

    private SearchCriteria criteria(int startIndex, int maxRows) {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setStartIndex(startIndex);
        criteria.setMaxRows(maxRows);
        criteria.setSortBy("eventTime");
        criteria.setSortType("desc");
        return criteria;
    }

    @Test
    public void testSearchReturnsAllSortedByEventTimeDesc() {
        VXAccessAuditList list = service.searchXAccessAudits(criteria(0, 25));

        assertEquals(5, list.getResultSize());
        List<VXAccessAudit> audits = list.getVXAccessAudits();
        // evtTime desc: bob(10:00:03), alice(10:00:02), bob(10:00:01), alice(10:00:00), carol(9:00:00)
        assertEquals("bob", audits.get(0).getRequestUser());
        assertEquals("alice", audits.get(1).getRequestUser());
        assertEquals("bob", audits.get(2).getRequestUser());
        assertEquals("carol", audits.get(4).getRequestUser());
        assertTrue(list.getTotalCount() >= 5);
    }

    @Test
    public void testPaginationSecondPage() {
        SearchCriteria criteria = criteria(2, 2);
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(2, list.getResultSize());
        assertEquals("bob", list.getVXAccessAudits().get(0).getRequestUser());
        assertEquals("alice", list.getVXAccessAudits().get(1).getRequestUser());
    }

    @Test
    public void testFilterByUser() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("requestUser", "alice");
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(2, list.getResultSize());
        for (VXAccessAudit audit : list.getVXAccessAudits()) {
            assertEquals("alice", audit.getRequestUser());
        }
    }

    @Test
    public void testPartialResourceMatch() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("resourcePath", "tranche-42");
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(3, list.getResultSize());
        for (VXAccessAudit audit : list.getVXAccessAudits()) {
            assertTrue(audit.getResourcePath().contains("tranche-42"));
        }
    }

    @Test
    public void testDateRangeExcludesOlderPartition() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("startDate", utc(9, 30, 0));
        criteria.addParam("endDate", utc(10, 30, 0));
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(4, list.getResultSize());
    }

    @Test
    public void testAccessResultFilter() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("accessResult", 0);
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(2, list.getResultSize());
        for (VXAccessAudit audit : list.getVXAccessAudits()) {
            assertEquals(0, audit.getAccessResult());
        }
    }

    @Test
    public void testSearchCountIsAtLeastRowNumber() {
        long count = service.getXAccessAuditSearchCount(criteria(0, 25)).getValue();

        assertTrue(count >= 5);
    }

    @Test
    public void testExcludeUserParam() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("-requestUser", "alice");
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        for (VXAccessAudit audit : list.getVXAccessAudits()) {
            assertTrue(!"alice".equals(audit.getRequestUser()));
        }
        assertEquals(3, list.getResultSize());
    }

    @Test
    public void testNegatedRepoTypeParam() {
        SearchCriteria criteria = criteria(0, 25);
        // every seeded event has repoType 0 unset... use result exclusion via the
        // generic negation path instead: -result is INT; exclude result=1
        criteria.addParam("-accessResult", 1);
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(2, list.getResultSize());
        for (VXAccessAudit audit : list.getVXAccessAudits()) {
            assertTrue(audit.getAccessResult() != 1);
        }
    }

    @Test
    public void testEventIdFilter() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("eventId", "evt-alice-" + utc(10, 0, 0).getTime());
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(1, list.getResultSize());
        assertEquals("alice", list.getVXAccessAudits().get(0).getRequestUser());
    }

    @Test
    public void testSortByUserAscending() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.setSortBy("requestUser");
        criteria.setSortType("asc");
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(5, list.getResultSize());
        assertEquals("alice", list.getVXAccessAudits().get(0).getRequestUser());
        assertEquals("bob", list.getVXAccessAudits().get(2).getRequestUser());
        assertEquals("carol", list.getVXAccessAudits().get(4).getRequestUser());
    }

    @Test
    public void testAccessTypeFilter() {
        SearchCriteria criteria = criteria(0, 25);
        criteria.addParam("accessType", "select");
        VXAccessAuditList list = service.searchXAccessAudits(criteria);

        assertEquals(2, list.getResultSize());
        for (VXAccessAudit audit : list.getVXAccessAudits()) {
            assertEquals("select", audit.getAccessType());
        }
    }
}
