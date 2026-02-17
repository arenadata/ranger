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

package org.apache.ranger.biz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenSecretManager;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.ranger.common.PropertiesUtil;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.XXRangerDTMasterKey;
import org.apache.ranger.entity.XXRangerDelegationToken;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RangerDelegationTokenSecretManager
        extends AbstractDelegationTokenSecretManager<RangerDelegationTokenIdentifier> {

    private static final Logger LOG = LoggerFactory.getLogger(RangerDelegationTokenSecretManager.class);

    private static final long DEFAULT_KEY_UPDATE_INTERVAL_SEC     = 86400;
    private static final long DEFAULT_TOKEN_MAX_LIFETIME_SEC      = 604800;
    private static final long DEFAULT_TOKEN_RENEW_INTERVAL_SEC    = 86400;
    private static final long DEFAULT_TOKEN_REMOVER_SCAN_INTERVAL_SEC = 3600;

    @Autowired
    private RangerDaoManager daoManager;

    private final boolean enabled;
    private volatile boolean started = false;

    public RangerDelegationTokenSecretManager() {
        super(
            PropertiesUtil.getLongProperty("ranger.admin.delegation-token.key.update-interval.sec", DEFAULT_KEY_UPDATE_INTERVAL_SEC) * 1000,
            PropertiesUtil.getLongProperty("ranger.admin.delegation-token.max-lifetime.sec", DEFAULT_TOKEN_MAX_LIFETIME_SEC) * 1000,
            PropertiesUtil.getLongProperty("ranger.admin.delegation-token.renew-interval.sec", DEFAULT_TOKEN_RENEW_INTERVAL_SEC) * 1000,
            PropertiesUtil.getLongProperty("ranger.admin.delegation-token.removal-scan-interval.sec", DEFAULT_TOKEN_REMOVER_SCAN_INTERVAL_SEC) * 1000
        );
        this.enabled = PropertiesUtil.getBooleanProperty("ranger.admin.delegation-token.enabled", false);
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            LOG.info("Ranger Delegation Token support is disabled");
            return;
        }
        LOG.info("Initializing RangerDelegationTokenSecretManager");
        try {
            loadFromDB();
            startThreads();
            started = true;
            LOG.info("RangerDelegationTokenSecretManager started successfully");
        } catch (IOException e) {
            LOG.error("Failed to start RangerDelegationTokenSecretManager. " +
                    "Delegation token support will be unavailable until restart.", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (enabled) {
            LOG.info("Shutting down RangerDelegationTokenSecretManager");
            try {
                stopThreads();
            } catch (Exception e) {
                LOG.warn("Error shutting down RangerDelegationTokenSecretManager", e);
            }
        }
    }

    public boolean isEnabled() {
        return enabled && started;
    }

    @Override
    public RangerDelegationTokenIdentifier createIdentifier() {
        return new RangerDelegationTokenIdentifier();
    }

    public Token<RangerDelegationTokenIdentifier> createDelegationToken(String owner, String renewer) throws IOException {
        Text ownerText   = new Text(owner);
        Text renewerText = renewer != null ? new Text(renewer) : null;
        RangerDelegationTokenIdentifier ident = new RangerDelegationTokenIdentifier(ownerText, renewerText, ownerText);

        Token<RangerDelegationTokenIdentifier> token = new Token<>(ident, this);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Created delegation token for owner={}, renewer={}, sequenceNumber={}", owner, renewer, ident.getSequenceNumber());
        }

        return token;
    }

    public long renewDelegationToken(Token<RangerDelegationTokenIdentifier> token, String callerUser) throws IOException {
        return renewToken(token, callerUser);
    }

    public void cancelDelegationToken(Token<RangerDelegationTokenIdentifier> token, String callerUser) throws IOException {
        cancelToken(token, callerUser);
    }

    public RangerDelegationTokenIdentifier verifyToken(Token<RangerDelegationTokenIdentifier> token) throws IOException {
        ByteArrayInputStream buf = new ByteArrayInputStream(token.getIdentifier());
        DataInputStream      in  = new DataInputStream(buf);

        RangerDelegationTokenIdentifier ident = createIdentifier();
        ident.readFields(in);

        byte[] expectedPassword = retrievePassword(ident);

        if (expectedPassword == null) {
            throw new IOException("Token is invalid or expired");
        }

        if (!MessageDigest.isEqual(expectedPassword, token.getPassword())) {
            throw new IOException("Token password does not match");
        }

        return ident;
    }

    @Override
    protected void storeNewMasterKey(DelegationKey key) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> storeNewMasterKey(keyId={})", key.getKeyId());
        }
        try {
            XXRangerDTMasterKey entity = new XXRangerDTMasterKey();
            entity.setKeyId(key.getKeyId());
            entity.setExpiryDate(key.getExpiryDate());
            entity.setKeyBytes(serializeDelegationKey(key));
            daoManager.getXXRangerDTMasterKey().create(entity);
        } catch (Exception e) {
            throw new IOException("Failed to store master key: keyId=" + key.getKeyId(), e);
        }
    }

    @Override
    protected void removeStoredMasterKey(DelegationKey key) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> removeStoredMasterKey(keyId={})", key.getKeyId());
        }
        try {
            daoManager.getXXRangerDTMasterKey().deleteByKeyId(key.getKeyId());
        } catch (Exception e) {
            LOG.warn("Failed to remove master key: keyId=" + key.getKeyId(), e);
        }
    }

    @Override
    protected void storeNewToken(RangerDelegationTokenIdentifier ident, long renewDate) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> storeNewToken(seqNum={}, owner={})", ident.getSequenceNumber(), ident.getOwner());
        }
        try {
            XXRangerDelegationToken entity = new XXRangerDelegationToken();
            entity.setSequenceNumber(ident.getSequenceNumber());
            entity.setOwner(ident.getOwner().toString());
            entity.setRenewer(ident.getRenewer() != null ? ident.getRenewer().toString() : null);
            entity.setRealUser(ident.getRealUser() != null ? ident.getRealUser().toString() : null);
            entity.setIssueDate(ident.getIssueDate());
            entity.setMaxDate(ident.getMaxDate());
            entity.setRenewDate(renewDate);
            entity.setMasterKeyId(ident.getMasterKeyId());
            entity.setTokenPassword(retrievePassword(ident));
            daoManager.getXXRangerDelegationToken().create(entity);
        } catch (Exception e) {
            throw new IOException("Failed to store token: seqNum=" + ident.getSequenceNumber(), e);
        }
    }

    @Override
    protected void updateStoredToken(RangerDelegationTokenIdentifier ident, long renewDate) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> updateStoredToken(seqNum={}, renewDate={})", ident.getSequenceNumber(), renewDate);
        }
        try {
            daoManager.getXXRangerDelegationToken().updateRenewDate(ident.getSequenceNumber(), renewDate);
        } catch (Exception e) {
            throw new IOException("Failed to update token: seqNum=" + ident.getSequenceNumber(), e);
        }
    }

    @Override
    protected void removeStoredToken(RangerDelegationTokenIdentifier ident) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> removeStoredToken(seqNum={})", ident.getSequenceNumber());
        }
        try {
            daoManager.getXXRangerDelegationToken().deleteBySequenceNumber(ident.getSequenceNumber());
        } catch (Exception e) {
            LOG.warn("Failed to remove token: seqNum=" + ident.getSequenceNumber(), e);
        }
    }

    private void loadFromDB() {
        LOG.info("Loading delegation token state from DB");

        List<XXRangerDTMasterKey> masterKeys = daoManager.getXXRangerDTMasterKey().findAll();
        if (masterKeys != null) {
            for (XXRangerDTMasterKey entity : masterKeys) {
                try {
                    DelegationKey key = deserializeDelegationKey(entity.getKeyBytes());
                    addKey(key);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Loaded master key: keyId={}", key.getKeyId());
                    }
                } catch (Exception e) {
                    LOG.error("Failed to load master key: keyId=" + entity.getKeyId(), e);
                }
            }
            LOG.info("Loaded {} master keys from DB", masterKeys.size());
        }

        List<XXRangerDelegationToken> tokens = daoManager.getXXRangerDelegationToken().findAll();
        if (tokens != null) {
            for (XXRangerDelegationToken entity : tokens) {
                try {
                    RangerDelegationTokenIdentifier ident = new RangerDelegationTokenIdentifier(
                            new Text(entity.getOwner()),
                            entity.getRenewer() != null ? new Text(entity.getRenewer()) : null,
                            entity.getRealUser() != null ? new Text(entity.getRealUser()) : null
                    );
                    ident.setIssueDate(entity.getIssueDate());
                    ident.setMaxDate(entity.getMaxDate());
                    ident.setSequenceNumber(entity.getSequenceNumber());
                    ident.setMasterKeyId(entity.getMasterKeyId());
                    addPersistedDelegationToken(ident, entity.getRenewDate());
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Loaded delegation token: seqNum={}, owner={}", entity.getSequenceNumber(), entity.getOwner());
                    }
                } catch (Exception e) {
                    LOG.error("Failed to load delegation token: seqNum=" + entity.getSequenceNumber(), e);
                }
            }
            LOG.info("Loaded {} delegation tokens from DB", tokens.size());
        }
    }

    private static byte[] serializeDelegationKey(DelegationKey key) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream      out = new DataOutputStream(bos);
        key.write(out);
        out.flush();
        return bos.toByteArray();
    }

    private static DelegationKey deserializeDelegationKey(byte[] data) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        DataInputStream      in  = new DataInputStream(bis);
        DelegationKey        key = new DelegationKey();
        key.readFields(in);
        return key;
    }
}
