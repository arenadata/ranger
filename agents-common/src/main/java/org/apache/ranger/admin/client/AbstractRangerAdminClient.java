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

package org.apache.ranger.admin.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.ranger.plugin.model.RangerRole;
import org.apache.ranger.plugin.model.ResourceMappingDiffs;
import org.apache.ranger.plugin.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

public abstract class AbstractRangerAdminClient implements RangerAdminClient {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractRangerAdminClient.class);

    protected Gson gson;

    private boolean forceNonKerberos = false;

    @Override
    public void init(String serviceName, String appId, String configPropertyPrefix, Configuration config) {
        Gson gson = null;

        try {
            gson = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSS-Z").setPrettyPrinting().create();
        } catch(Throwable excp) {
            LOG.error("AbstractRangerAdminClient: failed to create GsonBuilder object", excp);
        }

        this.gson = gson;
        this.forceNonKerberos = config.getBoolean(configPropertyPrefix + ".forceNonKerberos", false);
    }

    @Override
    public ServicePolicies getServicePoliciesIfUpdated(long lastKnownVersion, long lastActivationTimeInMillis) throws Exception {
        return null;
    }

    @Override
    public RangerRoles getRolesIfUpdated(long lastKnownRoleVersion, long lastActivationTimeInMillis) throws Exception {
        return null;
    }

    @Override
    public RangerRole createRole(RangerRole request) throws Exception {
        return null;
    }

    @Override
    public void dropRole(String execUser, String roleName) throws Exception {

    }

    @Override
    public List<String> getAllRoles(String execUser) throws Exception {
        return null;
    }

    @Override
    public List<String> getUserRoles(String execUser) throws Exception {
        return null;
    }

    @Override
    public RangerRole getRole(String execUser, String roleName) throws Exception {
        return null;
    }

    @Override
    public void grantRole(GrantRevokeRoleRequest request) throws Exception {

    }

    @Override
    public void revokeRole(GrantRevokeRoleRequest request) throws Exception {

    }

    @Override
    public void grantAccess(GrantRevokeRequest request) throws Exception {

    }

    @Override
    public void revokeAccess(GrantRevokeRequest request) throws Exception {

    }

    @Override
    public ServiceTags getServiceTagsIfUpdated(long lastKnownVersion, long lastActivationTimeInMillis) throws Exception {
        return null;
    }

    @Override
    public List<String> getTagTypes(String tagTypePattern) throws Exception {
        return null;
    }

    @Override
    public RangerUserStore getUserStoreIfUpdated(long lastKnownUserStoreVersion, long lastActivationTimeInMillis) throws Exception {
        return null;
    }

    @Override
    public ResourceMappingDiffs getResourceMappingDiffs(String sourceService, String targetService, Long diffId) throws Exception {
        return null;
    }

    @Override
    public Token<RangerDelegationTokenIdentifier> getDelegationToken(String renewer) throws Exception {
        return null;
    }

    @Override
    public long renewDelegationToken(Token<RangerDelegationTokenIdentifier> token) throws Exception {
        return 0;
    }

    @Override
    public void cancelDelegationToken(Token<RangerDelegationTokenIdentifier> token) throws Exception {
    }

    public boolean isKerberosEnabled(UserGroupInformation user) {
        final boolean ret;

        if (forceNonKerberos) {
            ret = false;
        } else {
            ret = user != null && UserGroupInformation.isSecurityEnabled() && user.hasKerberosCredentials();
        }

        return ret;
    }

    @SuppressWarnings("unchecked")
    public static Token<RangerDelegationTokenIdentifier> getDelegationTokenFromUGI() {
        try {
            UserGroupInformation ugi = UserGroupInformation.getLoginUser();
            if (ugi != null) {
                Collection<Token<?>> tokens = ugi.getCredentials().getAllTokens();
                for (Token<?> token : tokens) {
                    if (RangerDelegationTokenIdentifier.RANGER_DELEGATION_KIND.equals(token.getKind())) {
                        return (Token<RangerDelegationTokenIdentifier>) token;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to get delegation token from UGI", e);
        }
        return null;
    }
}
