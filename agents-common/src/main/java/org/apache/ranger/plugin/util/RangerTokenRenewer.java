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

package org.apache.ranger.plugin.util;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenRenewer;
import org.apache.ranger.admin.client.RangerAdminRESTClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RangerTokenRenewer extends TokenRenewer {
    private static final Logger LOG = LoggerFactory.getLogger(RangerTokenRenewer.class);

    private static final String CONFIG_PREFIX = "ranger.plugin.delegation-token";

    private volatile RangerAdminRESTClient client;

    @Override
    public boolean handleKind(Text kind) {
        return RangerDelegationTokenIdentifier.RANGER_DELEGATION_KIND.equals(kind);
    }

    @Override
    public boolean isManaged(Token<?> token) throws IOException {
        return handleKind(token.getKind());
    }

    @Override
    @SuppressWarnings("unchecked")
    public long renew(Token<?> token, Configuration conf) throws IOException, InterruptedException {
        LOG.info("Renewing Ranger delegation token");
        Token<RangerDelegationTokenIdentifier> rangerToken = (Token<RangerDelegationTokenIdentifier>) token;
        try {
            RangerAdminRESTClient adminClient = getOrCreateClient(conf, token);
            long newExpiry = adminClient.renewDelegationToken(rangerToken);
            LOG.info("Ranger delegation token renewed, new expiry={}", newExpiry);
            return newExpiry;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to renew Ranger delegation token", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void cancel(Token<?> token, Configuration conf) throws IOException, InterruptedException {
        LOG.info("Cancelling Ranger delegation token");
        Token<RangerDelegationTokenIdentifier> rangerToken = (Token<RangerDelegationTokenIdentifier>) token;
        try {
            RangerAdminRESTClient adminClient = getOrCreateClient(conf, token);
            adminClient.cancelDelegationToken(rangerToken);
            LOG.info("Ranger delegation token cancelled");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to cancel Ranger delegation token", e);
        }
    }

    private RangerAdminRESTClient getOrCreateClient(Configuration conf, Token<?> token) throws IOException {
        RangerAdminRESTClient ret = client;
        if (ret != null) {
            return ret;
        }

        synchronized (this) {
            if (client == null) {
                String rangerAdminUrl = token.getService().toString();
                if (rangerAdminUrl == null || rangerAdminUrl.isEmpty()) {
                    throw new IOException("Token does not have Ranger admin URL in service field");
                }

                conf.set(CONFIG_PREFIX + ".policy.rest.url", rangerAdminUrl);

                try {
                    RangerAdminRESTClient newClient = new RangerAdminRESTClient();
                    newClient.init("ranger", "tokenRenewer", CONFIG_PREFIX, conf);
                    client = newClient;
                } catch (Exception e) {
                    throw new IOException("Failed to create RangerAdminRESTClient for " + rangerAdminUrl, e);
                }
            }
            return client;
        }
    }
}
