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

package org.apache.ranger.plugin.audit;

import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Rendering of the chained-plugin attribution; the root service is not an input, so one set of assertions covers HDFS, Ozone and Trino roots. */
public class TestChainedDecisionAudit {
    private static final String CHAINED = "cl1_hive";

    /** scenario 1: chained plugin denied because no chained policy matched */
    @Test
    public void chainedDenyWithoutMatchingPolicy() {
        assertEquals("chained_service=cl1_hive chained_policy=not_found", reasonOf(CHAINED, -1L, false));
    }

    /** scenario 2: the marker names the decider, the allowing policy id stays in the event's policy field */
    @Test
    public void chainedAllowNamesTheDecider() {
        AuthzAuditEvent event = new RangerDefaultAuditHandler().getAuthzEvents(result(CHAINED, 1042L, true));

        assertEquals("chained_service=cl1_hive", event.getResultReason());
        assertEquals(1042L, event.getPolicyId());
    }

    /** an allow with no policy id (chained super-user) must not claim that no policy was found */
    @Test
    public void chainedAllowWithoutPolicyIdDoesNotClaimNotFound() {
        assertEquals("chained_service=cl1_hive", reasonOf(CHAINED, -1L, true));
    }

    /** scenario 3: no chained plugin involved - the event must stay exactly as it was */
    @Test
    public void decisionByRootServiceLeavesReasonUntouched() {
        assertNull(reasonOf(null, 88L, true));
    }

    private static String reasonOf(String chainedService, long policyId, boolean isAllowed) {
        return new RangerDefaultAuditHandler().getAuthzEvents(result(chainedService, policyId, isAllowed)).getResultReason();
    }

    private static RangerAccessResult result(String chainedService, long policyId, boolean isAllowed) {
        RangerAccessResult ret = new RangerAccessResult(RangerPolicy.POLICY_TYPE_ACCESS, "cl1_hdfs", null, new RangerAccessRequestImpl());

        ret.setIsAudited(true);
        ret.setIsAccessDetermined(true);
        ret.setIsAllowed(isAllowed);
        ret.setPolicyId(policyId);

        if (chainedService != null) {
            ret.setChainedServiceName(chainedService);
        }

        return ret;
    }
}
