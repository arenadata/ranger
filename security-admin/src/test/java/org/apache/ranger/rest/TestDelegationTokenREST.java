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

package org.apache.ranger.rest;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.WebApplicationException;

import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.token.Token;
import org.apache.ranger.biz.RangerBizUtil;
import org.apache.ranger.biz.RangerDelegationTokenSecretManager;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestDelegationTokenREST {

    @Mock
    private RangerDelegationTokenSecretManager secretManager;

    @Mock
    private RangerBizUtil bizUtil;

    @Mock
    private RESTErrorUtil restErrorUtil;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private DelegationTokenREST delegationTokenREST = new DelegationTokenREST();

    @Before
    public void setup() {
        Mockito.lenient().when(restErrorUtil.createRESTException(Mockito.anyInt(), Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> {
                    int status = invocation.getArgument(0);
                    return new WebApplicationException(javax.ws.rs.core.Response.status(status).build());
                });
        Mockito.lenient().when(restErrorUtil.createRESTException(Mockito.anyString()))
                .thenAnswer(invocation -> new WebApplicationException(
                        javax.ws.rs.core.Response.status(HttpServletResponse.SC_BAD_REQUEST).build()));
        Mockito.lenient().when(restErrorUtil.create403RESTException(Mockito.anyString()))
                .thenAnswer(invocation -> new WebApplicationException(
                        javax.ws.rs.core.Response.status(HttpServletResponse.SC_FORBIDDEN).build()));
    }

    // --- Get tests ---

    @Test
    public void testGetDelegationToken_disabled() {
        Mockito.when(secretManager.isEnabled()).thenReturn(false);

        try {
            delegationTokenREST.getDelegationToken("yarn", request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getResponse().getStatus());
        }
    }

    @Test
    public void testGetDelegationToken_noAuth() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn(null);

        try {
            delegationTokenREST.getDelegationToken("yarn", request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetDelegationToken_success() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");

        Token<RangerDelegationTokenIdentifier> mockToken = Mockito.mock(Token.class);
        Mockito.when(mockToken.encodeToUrlString()).thenReturn("encodedTokenString");
        Mockito.when(secretManager.createDelegationToken("testUser", "yarn")).thenReturn(mockToken);

        Map<String, String> result = delegationTokenREST.getDelegationToken("yarn", request);

        assertEquals("encodedTokenString", result.get("urlString"));
    }

    @Test
    public void testGetDelegationToken_internalError() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(secretManager.createDelegationToken(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new IOException("test error"));

        try {
            delegationTokenREST.getDelegationToken("yarn", request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            // error thrown via restErrorUtil
        }
    }

    @Test
    public void testGetDelegationToken_renewerTooLong() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");

        String longRenewer = new String(new char[300]).replace('\0', 'a');

        try {
            delegationTokenREST.getDelegationToken(longRenewer, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_BAD_REQUEST, e.getResponse().getStatus());
        }
    }

    // --- Renew tests ---

    @Test
    public void testRenewDelegationToken_disabled() {
        Mockito.when(secretManager.isEnabled()).thenReturn(false);

        Map<String, String> body = new HashMap<>();
        body.put("token", "someToken");

        try {
            delegationTokenREST.renewDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getResponse().getStatus());
        }
    }

    @Test
    public void testRenewDelegationToken_nullToken() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);

        try {
            delegationTokenREST.renewDelegationToken(null, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_BAD_REQUEST, e.getResponse().getStatus());
        }
    }

    @Test
    public void testRenewDelegationToken_noAuth() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn(null);

        Map<String, String> body = new HashMap<>();
        body.put("token", "someToken");

        try {
            delegationTokenREST.renewDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRenewDelegationToken_success() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(secretManager.renewDelegationToken(Mockito.any(Token.class), Mockito.eq("testUser")))
                .thenReturn(9999999L);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        String encoded = fakeToken.encodeToUrlString();

        Map<String, String> body = new HashMap<>();
        body.put("token", encoded);
        Map<String, Object> result = delegationTokenREST.renewDelegationToken(body, request);

        assertEquals(9999999L, result.get("expirationTime"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRenewDelegationToken_passesCallerIdentity() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(secretManager.renewDelegationToken(Mockito.any(Token.class), Mockito.eq("testUser")))
                .thenReturn(1L);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        delegationTokenREST.renewDelegationToken(body, request);

        Mockito.verify(secretManager).renewDelegationToken(Mockito.any(Token.class), Mockito.eq("testUser"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRenewDelegationToken_accessDenied() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(secretManager.renewDelegationToken(Mockito.any(Token.class), Mockito.anyString()))
                .thenThrow(new AccessControlException("not the designated renewer"));

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        try {
            delegationTokenREST.renewDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRenewDelegationToken_invalidToken() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(secretManager.renewDelegationToken(Mockito.any(Token.class), Mockito.anyString()))
                .thenThrow(new IOException("token is expired"));

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        try {
            delegationTokenREST.renewDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, e.getResponse().getStatus());
        }
    }

    // --- Cancel tests ---

    @Test
    public void testCancelDelegationToken_disabled() {
        Mockito.when(secretManager.isEnabled()).thenReturn(false);

        Map<String, String> body = new HashMap<>();
        body.put("token", "someToken");

        try {
            delegationTokenREST.cancelDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getResponse().getStatus());
        }
    }

    @Test
    public void testCancelDelegationToken_noAuth() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn(null);

        Map<String, String> body = new HashMap<>();
        body.put("token", "someToken");

        try {
            delegationTokenREST.cancelDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCancelDelegationToken_passesCallerIdentity() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        delegationTokenREST.cancelDelegationToken(body, request);

        Mockito.verify(secretManager).cancelDelegationToken(Mockito.any(Token.class), Mockito.eq("testUser"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCancelDelegationToken_accessDenied() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.doThrow(new AccessControlException("not authorized"))
                .when(secretManager).cancelDelegationToken(Mockito.any(Token.class), Mockito.anyString());

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        try {
            delegationTokenREST.cancelDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCancelDelegationToken_invalidToken() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.doThrow(new IOException("token does not exist"))
                .when(secretManager).cancelDelegationToken(Mockito.any(Token.class), Mockito.anyString());

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        try {
            delegationTokenREST.cancelDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, e.getResponse().getStatus());
        }
    }
}
