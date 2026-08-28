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

package org.apache.ranger.security.web.filter;

import static org.junit.Assert.*;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.apache.ranger.biz.RangerDelegationTokenSecretManager;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.Transient;
import org.springframework.security.core.context.SecurityContextHolder;

@RunWith(MockitoJUnitRunner.class)
public class TestRangerDelegationTokenAuthFilter {

    @Mock
    private RangerDelegationTokenSecretManager secretManager;

    @InjectMocks
    private RangerDelegationTokenAuthFilter filter = new RangerDelegationTokenAuthFilter();

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    @Before
    public void setup() {
        SecurityContextHolder.clearContext();
    }

    @After
    public void teardown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testPassThrough_whenDisabled() throws IOException, ServletException {
        Mockito.when(secretManager.isEnabled()).thenReturn(false);

        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void testPassThrough_whenAlreadyAuthenticated() throws IOException, ServletException {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("existingUser", "pass"));

        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
        assertEquals("existingUser", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    public void testPassThrough_whenNoTokenPresent() throws IOException, ServletException {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);
        Mockito.when(request.getHeader(RangerDelegationTokenAuthFilter.HEADER_DELEGATION_TOKEN)).thenReturn(null);
        Mockito.when(request.getParameter(RangerDelegationTokenAuthFilter.PARAM_DELEGATION_TOKEN)).thenReturn(null);

        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAuthentication_validTokenInHeader() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        String encoded = fakeToken.encodeToUrlString();

        Mockito.when(request.getHeader(RangerDelegationTokenAuthFilter.HEADER_DELEGATION_TOKEN))
                .thenReturn(encoded);

        RangerDelegationTokenIdentifier ident = new RangerDelegationTokenIdentifier(
                new Text("tokenOwner"), new Text("yarn"), new Text("tokenOwner"));
        Mockito.when(secretManager.verifyToken(Mockito.any(Token.class))).thenReturn(ident);

        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertTrue(auth.isAuthenticated());
        assertEquals("tokenOwner", auth.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAuthentication_validTokenInParam() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        String encoded = fakeToken.encodeToUrlString();

        Mockito.when(request.getHeader(RangerDelegationTokenAuthFilter.HEADER_DELEGATION_TOKEN))
                .thenReturn(null);
        Mockito.when(request.getParameter(RangerDelegationTokenAuthFilter.PARAM_DELEGATION_TOKEN))
                .thenReturn(encoded);

        RangerDelegationTokenIdentifier ident = new RangerDelegationTokenIdentifier(
                new Text("paramUser"), new Text("yarn"), new Text("paramUser"));
        Mockito.when(secretManager.verifyToken(Mockito.any(Token.class))).thenReturn(ident);

        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("paramUser", auth.getName());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAuthentication_invalidToken_returns401() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        String encoded = fakeToken.encodeToUrlString();

        Mockito.when(request.getHeader(RangerDelegationTokenAuthFilter.HEADER_DELEGATION_TOKEN))
                .thenReturn(encoded);
        Mockito.when(secretManager.verifyToken(Mockito.any(Token.class)))
                .thenThrow(new IOException("Token password does not match"));

        filter.doFilter(request, response, chain);

        Mockito.verify(chain, Mockito.never()).doFilter(request, response);
        Mockito.verify(response).sendError(
                Mockito.eq(HttpServletResponse.SC_UNAUTHORIZED),
                Mockito.anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAuthentication_setsRequestAttribute() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        String encoded = fakeToken.encodeToUrlString();

        Mockito.when(request.getHeader(RangerDelegationTokenAuthFilter.HEADER_DELEGATION_TOKEN))
                .thenReturn(encoded);

        RangerDelegationTokenIdentifier ident = new RangerDelegationTokenIdentifier(
                new Text("testUser"), new Text("yarn"), new Text("testUser"));
        Mockito.when(secretManager.verifyToken(Mockito.any(Token.class))).thenReturn(ident);

        filter.doFilter(request, response, chain);

        Mockito.verify(request).setAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED, true);
    }

    /**
     * The authentication must not reach the HTTP session, otherwise the session cookie
     * becomes a bearer credential that outlives the token. HttpSessionSecurityContextRepository
     * skips storing an Authentication whose class carries @Transient.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testAuthentication_isNotPersistedIntoSession() throws Exception {
        Mockito.when(secretManager.isEnabled()).thenReturn(true);

        Token<RangerDelegationTokenIdentifier> fakeToken = new Token<>();
        Mockito.when(request.getHeader(RangerDelegationTokenAuthFilter.HEADER_DELEGATION_TOKEN))
                .thenReturn(fakeToken.encodeToUrlString());

        RangerDelegationTokenIdentifier ident = new RangerDelegationTokenIdentifier(
                new Text("testUser"), new Text("yarn"), new Text("testUser"));
        Mockito.when(secretManager.verifyToken(Mockito.any(Token.class))).thenReturn(ident);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull("expected the filter to authenticate the request", auth);
        assertNotNull("delegation token authentication must be annotated @Transient",
                AnnotationUtils.getAnnotation(auth.getClass(), Transient.class));
        Mockito.verify(request, Mockito.never()).getSession(true);
        Mockito.verify(request, Mockito.never()).getSession();
    }
}
