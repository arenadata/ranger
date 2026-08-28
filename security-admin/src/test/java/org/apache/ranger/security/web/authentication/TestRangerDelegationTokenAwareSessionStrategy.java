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

package org.apache.ranger.security.web.authentication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import javax.servlet.http.HttpSession;

import org.apache.ranger.security.web.filter.RangerDelegationTokenAuthFilter;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

public class TestRangerDelegationTokenAwareSessionStrategy {

    private static final String SESSION_ATTRIBUTE = "AKA_SECURITY_CONTEXT";

    private final RangerDelegationTokenAwareSessionStrategy strategy = new RangerDelegationTokenAwareSessionStrategy();

    private final Authentication authentication = new UsernamePasswordAuthenticationToken("testUser", "");

    private MockHttpServletRequest requestWithExistingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_ATTRIBUTE, "cached");
        request.setRequestedSessionId(session.getId());
        request.setRequestedSessionIdValid(true);

        return request;
    }

    @Test
    public void testDelegationTokenRequestKeepsItsSession() {
        MockHttpServletRequest request = requestWithExistingSession();
        request.setAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED, true);

        String sessionIdBefore = request.getSession(false).getId();

        strategy.onAuthentication(authentication, request, new MockHttpServletResponse());

        assertEquals("delegation token request must keep its session",
                sessionIdBefore, request.getSession(false).getId());
        assertEquals("the cached security context must survive",
                "cached", request.getSession(false).getAttribute(SESSION_ATTRIBUTE));
    }

    @Test
    public void testOtherAuthenticationStillGetsFixationProtection() {
        MockHttpServletRequest request = requestWithExistingSession();

        String sessionIdBefore = request.getSession(false).getId();

        strategy.onAuthentication(authentication, request, new MockHttpServletResponse());

        assertNotEquals("a normal login must still get a fresh session",
                sessionIdBefore, request.getSession(false).getId());
        assertNull("newSession semantics: attributes are not migrated",
                request.getSession(false).getAttribute(SESSION_ATTRIBUTE));
    }
}
