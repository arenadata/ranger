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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.token.Token;
import org.apache.ranger.biz.RangerBizUtil;
import org.apache.ranger.biz.RangerDelegationTokenSecretManager;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Path("delegation-token")
@Component
@Scope("request")
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class DelegationTokenREST {
    private static final Logger LOG = LoggerFactory.getLogger(DelegationTokenREST.class);

    private static final int MAX_RENEWER_LENGTH = 255;

    @Autowired
    RangerDelegationTokenSecretManager secretManager;

    @Autowired
    RangerBizUtil bizUtil;

    @Autowired
    RESTErrorUtil restErrorUtil;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> getDelegationToken(@QueryParam("renewer") String renewer,
                                                   @Context HttpServletRequest request) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> DelegationTokenREST.getDelegationToken(renewer={})", renewer);
        }

        if (!secretManager.isEnabled()) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Delegation token support is not enabled", true);
        }

        String authenticatedUser = bizUtil.getCurrentUserLoginId();
        if (authenticatedUser == null) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication required", true);
        }

        if (renewer != null && renewer.length() > MAX_RENEWER_LENGTH) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_BAD_REQUEST,
                    "Renewer name is too long", true);
        }

        try {
            Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken(authenticatedUser, renewer);

            Map<String, String> result = new HashMap<>();
            result.put("urlString", token.encodeToUrlString());

            LOG.info("Delegation token created for user={}, renewer={}", authenticatedUser, renewer);

            return result;
        } catch (Exception e) {
            LOG.error("Failed to create delegation token for user=" + authenticatedUser, e);
            throw restErrorUtil.createRESTException("Failed to create delegation token");
        }
    }

    @PUT
    @Path("/renew")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> renewDelegationToken(Map<String, String> requestBody,
                                                     @Context HttpServletRequest request) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> DelegationTokenREST.renewDelegationToken()");
        }

        if (!secretManager.isEnabled()) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Delegation token support is not enabled", true);
        }

        String tokenEncoded = requestBody != null ? requestBody.get("token") : null;
        if (tokenEncoded == null || tokenEncoded.isEmpty()) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_BAD_REQUEST,
                    "Token parameter is required", true);
        }

        String authenticatedUser = bizUtil.getCurrentUserLoginId();
        if (authenticatedUser == null) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication required", true);
        }

        try {
            Token<RangerDelegationTokenIdentifier> token = new Token<>();
            token.decodeFromUrlString(tokenEncoded);

            long newExpiryTime = secretManager.renewDelegationToken(token, authenticatedUser);

            Map<String, Object> result = new HashMap<>();
            result.put("expirationTime", newExpiryTime);

            LOG.info("Delegation token renewed by user={}, newExpiryTime={}", authenticatedUser, newExpiryTime);

            return result;
        } catch (AccessControlException e) {
            LOG.warn("Delegation token renewal denied for user={}", authenticatedUser);
            throw restErrorUtil.create403RESTException("Delegation token renewal denied");
        } catch (IOException e) {
            LOG.warn("Delegation token renewal failed: {}", e.getMessage());
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_UNAUTHORIZED,
                    "Delegation token is invalid or expired", true);
        } catch (Exception e) {
            LOG.error("Failed to renew delegation token", e);
            throw restErrorUtil.createRESTException("Internal error during delegation token renewal");
        }
    }

    @PUT
    @Path("/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public void cancelDelegationToken(Map<String, String> requestBody,
                                       @Context HttpServletRequest request) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> DelegationTokenREST.cancelDelegationToken()");
        }

        if (!secretManager.isEnabled()) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Delegation token support is not enabled", true);
        }

        String tokenEncoded = requestBody != null ? requestBody.get("token") : null;
        if (tokenEncoded == null || tokenEncoded.isEmpty()) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_BAD_REQUEST,
                    "Token parameter is required", true);
        }

        String authenticatedUser = bizUtil.getCurrentUserLoginId();
        if (authenticatedUser == null) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication required", true);
        }

        try {
            Token<RangerDelegationTokenIdentifier> token = new Token<>();
            token.decodeFromUrlString(tokenEncoded);

            secretManager.cancelDelegationToken(token, authenticatedUser);

            LOG.info("Delegation token cancelled by user={}", authenticatedUser);
        } catch (AccessControlException e) {
            LOG.warn("Delegation token cancellation denied for user={}", authenticatedUser);
            throw restErrorUtil.create403RESTException("Delegation token cancellation denied");
        } catch (IOException e) {
            LOG.warn("Delegation token cancellation failed: {}", e.getMessage());
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_UNAUTHORIZED,
                    "Delegation token is invalid or expired", true);
        } catch (Exception e) {
            LOG.error("Failed to cancel delegation token", e);
            throw restErrorUtil.createRESTException("Internal error during delegation token cancellation");
        }
    }
}
