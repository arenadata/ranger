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

import static org.junit.Assert.assertEquals;

import javax.servlet.http.HttpServletRequest;

import org.apache.ranger.entity.XXAuthSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestRangerSecurityContextFormationFilter {

	@Mock
	private HttpServletRequest request;

	private final RangerSecurityContextFormationFilter filter = new RangerSecurityContextFormationFilter();

	@Test
	public void testGetAuthType_delegationToken() {
		Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn(true);

		assertEquals(XXAuthSession.AUTH_TYPE_DELEGATION_TOKEN, filter.getAuthType(request));
	}

	/**
	 * The SSO filter leaves the ssoEnabled attribute unset for an already-authenticated
	 * request, so an SSO deployment must not mask delegation token auth.
	 */
	@Test
	public void testGetAuthType_delegationTokenWinsOverSso() {
		Mockito.when(request.getAttribute("ssoEnabled")).thenReturn(true);
		Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn(true);

		assertEquals(XXAuthSession.AUTH_TYPE_DELEGATION_TOKEN, filter.getAuthType(request));
	}

	@Test
	public void testGetAuthType_delegationTokenAsString() {
		Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn("true");

		assertEquals(XXAuthSession.AUTH_TYPE_DELEGATION_TOKEN, filter.getAuthType(request));
	}

	@Test
	public void testGetAuthType_delegationTokenFalseFallsThrough() {
		Mockito.when(request.getAttribute("ssoEnabled")).thenReturn(true);
		Mockito.when(request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED)).thenReturn(false);

		assertEquals(XXAuthSession.AUTH_TYPE_SSO, filter.getAuthType(request));
	}

	@Test
	public void testGetAuthType_sso() {
		Mockito.when(request.getAttribute("ssoEnabled")).thenReturn(true);

		assertEquals(XXAuthSession.AUTH_TYPE_SSO, filter.getAuthType(request));
	}

	@Test
	public void testGetAuthType_kerberos() {
		Mockito.when(request.getAttribute("ssoEnabled")).thenReturn(false);
		Mockito.when(request.getAttribute("spnegoEnabled")).thenReturn(true);

		assertEquals(XXAuthSession.AUTH_TYPE_KERBEROS, filter.getAuthType(request));
	}

	@Test
	public void testGetAuthType_trustedProxy() {
		Mockito.when(request.getAttribute("ssoEnabled")).thenReturn(false);
		Mockito.when(request.getAttribute("spnegoEnabled")).thenReturn(true);
		Mockito.when(request.getAttribute("trustedProxyEnabled")).thenReturn(true);

		assertEquals(XXAuthSession.AUTH_TYPE_TRUSTED_PROXY, filter.getAuthType(request));
	}

	@Test
	public void testGetAuthType_password() {
		Mockito.when(request.getAttribute("ssoEnabled")).thenReturn(false);

		assertEquals(XXAuthSession.AUTH_TYPE_PASSWORD, filter.getAuthType(request));
	}
}
