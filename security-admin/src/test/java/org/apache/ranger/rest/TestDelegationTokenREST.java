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
import org.apache.ranger.common.UserSessionBase;
import org.apache.ranger.entity.XXAuthSession;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.apache.ranger.security.context.RangerContextHolder;
import org.apache.ranger.security.context.RangerSecurityContext;
import org.apache.ranger.security.web.filter.RangerDelegationTokenAuthFilter;
import org.junit.After;
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
        RangerContextHolder.resetSecurityContext();
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

    @After
    public void teardown() {
        RangerContextHolder.resetSecurityContext();
    }

    private void setSessionAuthType(int authType) {
        XXAuthSession authSession = new XXAuthSession();
        authSession.setAuthType(authType);

        UserSessionBase userSession = new UserSessionBase();
        userSession.setXXAuthSession(authSession);

        RangerSecurityContext context = new RangerSecurityContext();
        context.setUserSession(userSession);
        RangerContextHolder.setSecurityContext(context);
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

    @Test
    public void testGetDelegationToken_deniedForTokenAuthRequest() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn(true);

        try {
            delegationTokenREST.getDelegationToken("yarn", request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
        Mockito.verify(secretManager, Mockito.never()).createDelegationToken(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void testGetDelegationToken_deniedForTokenAuthSession() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        setSessionAuthType(XXAuthSession.AUTH_TYPE_DELEGATION_TOKEN);

        try {
            delegationTokenREST.getDelegationToken("yarn", request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetDelegationToken_allowedWhenAttributeIsFalse() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn(false);

        Token<RangerDelegationTokenIdentifier> mockToken = Mockito.mock(Token.class);
        Mockito.when(mockToken.encodeToUrlString()).thenReturn("encodedTokenString");
        Mockito.when(secretManager.createDelegationToken("testUser", "yarn")).thenReturn(mockToken);

        Map<String, String> result = delegationTokenREST.getDelegationToken("yarn", request);

        assertEquals("encodedTokenString", result.get("urlString"));
    }

    @Test
    public void testGetDelegationToken_deniedForStringAttributeValue() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn("true");

        try {
            delegationTokenREST.getDelegationToken("yarn", request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
        Mockito.verify(secretManager, Mockito.never()).createDelegationToken(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetDelegationToken_allowedWhenSessionHasNoAuthSession() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");

        RangerSecurityContext context = new RangerSecurityContext();
        context.setUserSession(new UserSessionBase());
        RangerContextHolder.setSecurityContext(context);

        Token<RangerDelegationTokenIdentifier> mockToken = Mockito.mock(Token.class);
        Mockito.when(mockToken.encodeToUrlString()).thenReturn("encodedTokenString");
        Mockito.when(secretManager.createDelegationToken("testUser", "yarn")).thenReturn(mockToken);

        Map<String, String> result = delegationTokenREST.getDelegationToken("yarn", request);

        assertEquals("encodedTokenString", result.get("urlString"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetDelegationToken_allowedForPasswordAuthSession() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        setSessionAuthType(XXAuthSession.AUTH_TYPE_PASSWORD);

        Token<RangerDelegationTokenIdentifier> mockToken = Mockito.mock(Token.class);
        Mockito.when(mockToken.encodeToUrlString()).thenReturn("encodedTokenString");
        Mockito.when(secretManager.createDelegationToken("testUser", "yarn")).thenReturn(mockToken);

        Map<String, String> result = delegationTokenREST.getDelegationToken("yarn", request);

        assertEquals("encodedTokenString", result.get("urlString"));
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

    @Test
    public void testRenewDelegationToken_deniedForTokenAuthRequest() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn(true);

        Map<String, String> body = new HashMap<>();
        body.put("token", "someToken");

        try {
            delegationTokenREST.renewDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
        Mockito.verify(secretManager, Mockito.never()).renewDelegationToken(Mockito.any(), Mockito.anyString());
    }

    @Test
    public void testRenewDelegationToken_deniedForTokenAuthSession() {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        setSessionAuthType(XXAuthSession.AUTH_TYPE_DELEGATION_TOKEN);

        Map<String, String> body = new HashMap<>();
        body.put("token", "someToken");

        try {
            delegationTokenREST.renewDelegationToken(body, request);
            fail("Expected WebApplicationException");
        } catch (WebApplicationException e) {
            assertEquals(HttpServletResponse.SC_FORBIDDEN, e.getResponse().getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRenewDelegationToken_allowedForPasswordAuthSession() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        setSessionAuthType(XXAuthSession.AUTH_TYPE_PASSWORD);
        Mockito.when(secretManager.renewDelegationToken(Mockito.any(Token.class), Mockito.eq("testUser")))
                .thenReturn(1L);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        Map<String, Object> result = delegationTokenREST.renewDelegationToken(body, request);

        assertEquals(1L, result.get("expirationTime"));
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
    public void testCancelDelegationToken_allowedForTokenAuthSession() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(bizUtil.getCurrentUserLoginId()).thenReturn("testUser");
        setSessionAuthType(XXAuthSession.AUTH_TYPE_DELEGATION_TOKEN);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Map<String, String> body = new HashMap<>();
        body.put("token", fakeToken.encodeToUrlString());

        delegationTokenREST.cancelDelegationToken(body, request);

        Mockito.verify(secretManager).cancelDelegationToken(Mockito.any(Token.class), Mockito.eq("testUser"));
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
