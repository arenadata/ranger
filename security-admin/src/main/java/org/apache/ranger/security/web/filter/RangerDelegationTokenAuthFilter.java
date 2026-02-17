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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.security.token.Token;
import org.apache.ranger.biz.RangerDelegationTokenSecretManager;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.filter.GenericFilterBean;

public class RangerDelegationTokenAuthFilter extends GenericFilterBean {
    private static final Logger LOG = LoggerFactory.getLogger(RangerDelegationTokenAuthFilter.class);

    public static final String HEADER_DELEGATION_TOKEN = "X-Delegation-Token-Encoded";
    public static final String PARAM_DELEGATION_TOKEN  = "delegationToken";

    private static final String DEFAULT_ROLE = "ROLE_USER";

    @Autowired
    RangerDelegationTokenSecretManager secretManager;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpRequest  = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!secretManager.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String tokenEncoded = httpRequest.getHeader(HEADER_DELEGATION_TOKEN);
        if (StringUtils.isEmpty(tokenEncoded)) {
            tokenEncoded = httpRequest.getParameter(PARAM_DELEGATION_TOKEN);
        }

        if (StringUtils.isEmpty(tokenEncoded)) {
            chain.doFilter(request, response);
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("==> RangerDelegationTokenAuthFilter: found delegation token in request for URI={}", httpRequest.getRequestURI());
        }

        try {
            Token<RangerDelegationTokenIdentifier> token = new Token<>();
            token.decodeFromUrlString(tokenEncoded);

            RangerDelegationTokenIdentifier ident = secretManager.verifyToken(token);
            String userName = ident.getUser().getShortUserName();

            if (LOG.isDebugEnabled()) {
                LOG.debug("Delegation token verified for user={}", userName);
            }

            final List<GrantedAuthority> grantedAuths = new ArrayList<>();
            grantedAuths.add(new SimpleGrantedAuthority(DEFAULT_ROLE));

            final UserDetails principal = new User(userName, "", grantedAuths);
            final Authentication authentication = new UsernamePasswordAuthenticationToken(principal, "", grantedAuths);
            WebAuthenticationDetails webDetails = new WebAuthenticationDetails(httpRequest);
            ((AbstractAuthenticationToken) authentication).setDetails(webDetails);

            SecurityContextHolder.getContext().setAuthentication(authentication);
            httpRequest.setAttribute("delegationTokenEnabled", true);
            httpRequest.getSession(true);

            if (LOG.isDebugEnabled()) {
                LOG.debug("<== RangerDelegationTokenAuthFilter: authenticated user={} via delegation token", userName);
            }

            chain.doFilter(request, response);
        } catch (Exception e) {
            LOG.warn("Delegation token authentication failed: {}", e.getMessage());
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Delegation token authentication failed");
        }
    }
}
