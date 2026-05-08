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

import org.apache.ranger.biz.UserMgr;
import org.apache.ranger.common.PropertiesUtil;
import org.apache.ranger.common.RangerConstants;
import org.apache.ranger.entity.XXPortalUser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestRangerKRBAuthenticationFilter {
    private RangerKRBAuthenticationFilter filter;
    private UserMgr                       userMgr;
    private HttpServletResponse           response;

    @Before
    public void setUp() {
        filter = new RangerKRBAuthenticationFilter();
        userMgr = Mockito.mock(UserMgr.class);
        filter.userMgr = userMgr;
        response = Mockito.mock(HttpServletResponse.class);
    }

    @After
    public void tearDown() {
        PropertiesUtil.getPropertiesMap().remove(RangerKRBAuthenticationFilter.ALLOW_UNREGISTERED_USER);
    }

    @Test
    public void registeredActiveUser_isAuthorized() throws Exception {
        Mockito.when(userMgr.findByLoginId("alice")).thenReturn(activeUser());

        assertTrue(filter.isUserAuthorizedForRanger("alice", response));

        Mockito.verify(response, Mockito.never()).sendError(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    public void unregisteredUser_isRejected_whenStrict() throws Exception {
        PropertiesUtil.getPropertiesMap().put(RangerKRBAuthenticationFilter.ALLOW_UNREGISTERED_USER, "false");
        Mockito.when(userMgr.findByLoginId("ghost")).thenReturn(null);

        assertFalse(filter.isUserAuthorizedForRanger("ghost", response));

        Mockito.verify(response).sendError(Mockito.eq(HttpServletResponse.SC_FORBIDDEN), ArgumentMatchers.contains("not registered"));
    }

    @Test
    public void unregisteredUser_isAllowed_whenLegacyFlagOn() throws Exception {
        PropertiesUtil.getPropertiesMap().put(RangerKRBAuthenticationFilter.ALLOW_UNREGISTERED_USER, "true");
        Mockito.when(userMgr.findByLoginId("ghost")).thenReturn(null);

        assertTrue(filter.isUserAuthorizedForRanger("ghost", response));

        Mockito.verify(response, Mockito.never()).sendError(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    public void deactivatedUser_isAlwaysRejected() throws Exception {
        PropertiesUtil.getPropertiesMap().put(RangerKRBAuthenticationFilter.ALLOW_UNREGISTERED_USER, "true");
        Mockito.when(userMgr.findByLoginId("zombie")).thenReturn(deactivatedUser());

        assertFalse(filter.isUserAuthorizedForRanger("zombie", response));

        Mockito.verify(response).sendError(Mockito.eq(HttpServletResponse.SC_FORBIDDEN), ArgumentMatchers.contains("not active"));
    }

    @Test
    public void emptyLoginId_isRejected() throws Exception {
        assertFalse(filter.isUserAuthorizedForRanger("", response));
        assertFalse(filter.isUserAuthorizedForRanger(null, response));

        Mockito.verify(response, Mockito.times(2)).sendError(Mockito.eq(HttpServletResponse.SC_FORBIDDEN), Mockito.anyString());
        Mockito.verifyZeroInteractions(userMgr);
    }

    private static XXPortalUser activeUser() {
        XXPortalUser u = new XXPortalUser();
        u.setStatus(RangerConstants.ACT_STATUS_ACTIVE);
        return u;
    }

    private static XXPortalUser deactivatedUser() {
        XXPortalUser u = new XXPortalUser();
        u.setStatus(RangerConstants.ACT_STATUS_DEACTIVATED);
        return u;
    }
}
