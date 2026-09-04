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

package org.apache.ranger.authorization.hadoop;

import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerResourceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;

/**
 * The HDFS handler owns resultReason and folds several processResult() calls into one event,
 * rewriting resultReason each time: the marker must track the last check and must not outlive it.
 * flushAudit() is not exercised - it calls super.logAuthzAudit(), which an override cannot intercept.
 */
public class TestRangerHdfsAuditHandlerChained {
    private static final String CHAINED_SERVICE = "cl1_hive";
    private static final String MARKER          = "chained_service=cl1_hive chained_policy=not_found";

    /** ancestor check decided by HDFS, the inode check decided by the chained plugin */
    @Test
    public void markerTracksTheDecidingResult() throws Exception {
        RangerHdfsAuditHandler handler = newHandler();

        handler.processResult(result("/warehouse/db1", 88L, true, null));
        handler.processResult(result("/warehouse/db1/table1", -1L, false, CHAINED_SERVICE));

        assertEquals("/warehouse/db1/table1 " + MARKER, auditEventOf(handler).getResultReason());
    }

    /** reverse order: a marker from an earlier check must not leak onto a later root decision */
    @Test
    public void staleMarkerDoesNotLeak() throws Exception {
        RangerHdfsAuditHandler handler = newHandler();

        handler.processResult(result("/warehouse/db1", -1L, false, CHAINED_SERVICE));
        handler.processResult(result("/warehouse/db1/table1", 88L, true, null));

        assertEquals("/warehouse/db1/table1", auditEventOf(handler).getResultReason());
    }

    private RangerHdfsAuditHandler newHandler() {
        return new RangerHdfsAuditHandler("/warehouse/db1/table1", false, "hadoop-acl", Collections.<String>emptySet(), null);
    }

    private static AuthzAuditEvent auditEventOf(RangerHdfsAuditHandler handler) throws Exception {
        Field field = RangerHdfsAuditHandler.class.getDeclaredField("auditEvent");

        field.setAccessible(true);

        return (AuthzAuditEvent) field.get(handler);
    }

    private RangerAccessResult result(String path, long policyId, boolean isAllowed, String chainedService) {
        RangerAccessResult ret = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, "cl1_hdfs", serviceDef(), request(path));

        ret.setIsAudited(true);
        ret.setIsAccessDetermined(true);
        ret.setIsAllowed(isAllowed);
        ret.setPolicyId(policyId);

        if (chainedService != null) {
            ret.setChainedServiceName(chainedService);
        }

        return ret;
    }

    private RangerAccessRequestImpl request(String path) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl();

        resource.setServiceDef(serviceDef());
        resource.setValue("path", path);

        RangerAccessRequestImpl ret = new RangerAccessRequestImpl();

        ret.setResource(resource);
        ret.setAccessType("read");
        ret.setAction("read");
        ret.setUser("svc1");
        ret.setAccessTime(new Date(0L));

        return ret;
    }

    private RangerServiceDef serviceDef() {
        RangerResourceDef pathDef = new RangerResourceDef();

        pathDef.setName("path");

        RangerServiceDef ret = new RangerServiceDef();

        ret.setName("hdfs");
        ret.setResources(Collections.singletonList(pathDef));

        return ret;
    }
}
