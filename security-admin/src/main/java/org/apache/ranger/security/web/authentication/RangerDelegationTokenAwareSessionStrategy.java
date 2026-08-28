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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ranger.security.web.filter.RangerDelegationTokenAuthFilter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;

/**
 * Applies session fixation protection to everything except delegation token requests.
 *
 * Token authentication is never stored in the session, so SessionManagementFilter sees every
 * such request as a fresh login and would otherwise recreate the session each time, discarding
 * the cached RangerSecurityContext. A token establishes no session to fixate.
 */
public class RangerDelegationTokenAwareSessionStrategy implements SessionAuthenticationStrategy {

    private final SessionFixationProtectionStrategy delegate = new SessionFixationProtectionStrategy();

    public RangerDelegationTokenAwareSessionStrategy() {
        // preserves the behaviour of session-fixation-protection="newSession"
        delegate.setMigrateSessionAttributes(false);
    }

    @Override
    public void onAuthentication(Authentication authentication, HttpServletRequest request,
                                 HttpServletResponse response) {
        Object viaDelegationToken = request.getAttribute(RangerDelegationTokenAuthFilter.ATTR_DELEGATION_TOKEN_ENABLED);

        if (Boolean.parseBoolean(String.valueOf(viaDelegationToken))) {
            return;
        }

        delegate.onAuthentication(authentication, request, response);
    }
}
