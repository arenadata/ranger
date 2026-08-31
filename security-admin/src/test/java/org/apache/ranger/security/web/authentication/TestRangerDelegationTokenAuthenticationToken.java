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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Verifies the behaviour the delegation token guard depends on, not merely that the
 * annotation is present: Spring must decline to store this authentication in the session.
 */
public class TestRangerDelegationTokenAuthenticationToken {

    private static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    private Object saveAndReadBack(Authentication authentication) {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // loadContext installs the response wrapper that saveContext looks for
        SecurityContext context = repository.loadContext(
                new org.springframework.security.web.context.HttpRequestResponseHolder(request, response));
        context.setAuthentication(authentication);

        repository.saveContext(context, request, response);

        return request.getSession(true).getAttribute(SPRING_SECURITY_CONTEXT_KEY);
    }

    @Test
    public void testDelegationTokenAuthenticationIsNotStoredInSession() {
        Authentication authentication = new RangerDelegationTokenAuthenticationToken(
                "testUser", "", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertNull("delegation token authentication must never reach the HTTP session",
                saveAndReadBack(authentication));
    }

    /**
     * Control case: without it the first test would also pass if saving were broken outright.
     */
    @Test
    public void testOrdinaryAuthenticationIsStoredInSession() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "testUser", "", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertNotNull("ordinary authentication is expected to be stored in the HTTP session",
                saveAndReadBack(authentication));
    }

    @org.junit.After
    public void teardown() {
        SecurityContextHolder.clearContext();
    }
}
