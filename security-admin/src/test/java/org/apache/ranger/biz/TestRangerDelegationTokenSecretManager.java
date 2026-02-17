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

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Collections;

import org.apache.hadoop.security.token.Token;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.db.XXRangerDTMasterKeyDao;
import org.apache.ranger.db.XXRangerDelegationTokenDao;
import org.apache.ranger.entity.XXRangerDTMasterKey;
import org.apache.ranger.entity.XXRangerDelegationToken;
import org.apache.ranger.plugin.util.RangerDelegationTokenIdentifier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class TestRangerDelegationTokenSecretManager {

    @Mock
    private RangerDaoManager daoManager;

    @Mock
    private XXRangerDTMasterKeyDao masterKeyDao;

    @Mock
    private XXRangerDelegationTokenDao tokenDao;

    private RangerDelegationTokenSecretManager secretManager;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        secretManager = new TestableSecretManager();

        Mockito.when(daoManager.getXXRangerDTMasterKey()).thenReturn(masterKeyDao);
        Mockito.when(daoManager.getXXRangerDelegationToken()).thenReturn(tokenDao);
        Mockito.when(masterKeyDao.findAll()).thenReturn(Collections.emptyList());
        Mockito.when(tokenDao.findAll()).thenReturn(Collections.emptyList());
        Mockito.when(masterKeyDao.create(Mockito.any(XXRangerDTMasterKey.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(tokenDao.create(Mockito.any(XXRangerDelegationToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        java.lang.reflect.Field daoField = RangerDelegationTokenSecretManager.class.getDeclaredField("daoManager");
        daoField.setAccessible(true);
        daoField.set(secretManager, daoManager);

        secretManager.startThreads();
    }

    @Test
    public void testCreateAndVerifyToken() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("testUser", "yarn");

        assertNotNull(token);
        assertNotNull(token.getIdentifier());
        assertNotNull(token.getPassword());
        assertTrue(token.getPassword().length > 0);

        RangerDelegationTokenIdentifier ident = secretManager.verifyToken(token);
        assertNotNull(ident);
        assertEquals("testUser", ident.getOwner().toString());
        assertEquals("yarn", ident.getRenewer().toString());
    }

    @Test
    public void testVerifyTokenWithWrongPassword() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("testUser", "yarn");

        Token<RangerDelegationTokenIdentifier> tamperedToken = new Token<>(
                token.getIdentifier(),
                new byte[]{1, 2, 3, 4},  // wrong password
                token.getKind(),
                token.getService()
        );

        try {
            secretManager.verifyToken(tamperedToken);
            fail("Should have thrown IOException for wrong password");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("password does not match"));
        }
    }

    @Test
    public void testRenewTokenByDesignatedRenewer() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("owner", "yarn");

        long newExpiry = secretManager.renewDelegationToken(token, "yarn");
        assertTrue(newExpiry > System.currentTimeMillis());
    }

    @Test
    public void testRenewTokenByNonRenewer() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("owner", "yarn");

        try {
            secretManager.renewDelegationToken(token, "attacker");
            fail("Should have thrown IOException for non-renewer");
        } catch (Exception e) {
            // Expected: Hadoop throws AccessControlException wrapped in IOException
        }
    }

    @Test
    public void testCancelTokenByOwner() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("owner", "yarn");

        secretManager.cancelDelegationToken(token, "owner");

        try {
            secretManager.verifyToken(token);
            fail("Should have thrown IOException for cancelled token");
        } catch (IOException e) {
            // Expected
        }
    }

    @Test
    public void testCancelTokenByRenewer() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("owner", "yarn");

        secretManager.cancelDelegationToken(token, "yarn");

        try {
            secretManager.verifyToken(token);
            fail("Should have thrown IOException for cancelled token");
        } catch (IOException e) {
            // Expected
        }
    }

    @Test
    public void testCancelTokenByNonOwner() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("owner", "yarn");

        try {
            secretManager.cancelDelegationToken(token, "attacker");
            fail("Should have thrown exception for non-owner/non-renewer cancel");
        } catch (Exception e) {
            // Expected: Hadoop throws AccessControlException
        }
    }

    @Test
    public void testCreateTokenWithNullRenewer() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("testUser", null);

        assertNotNull(token);

        RangerDelegationTokenIdentifier ident = secretManager.verifyToken(token);
        assertEquals("testUser", ident.getOwner().toString());
    }

    @Test
    public void testMultipleTokensIndependent() throws Exception {
        Token<RangerDelegationTokenIdentifier> token1 = secretManager.createDelegationToken("user1", "yarn");
        Token<RangerDelegationTokenIdentifier> token2 = secretManager.createDelegationToken("user2", "yarn");

        RangerDelegationTokenIdentifier ident1 = secretManager.verifyToken(token1);
        RangerDelegationTokenIdentifier ident2 = secretManager.verifyToken(token2);

        assertEquals("user1", ident1.getOwner().toString());
        assertEquals("user2", ident2.getOwner().toString());

        secretManager.cancelDelegationToken(token1, "user1");

        try {
            secretManager.verifyToken(token1);
            fail("Cancelled token should not verify");
        } catch (IOException e) {
            // Expected
        }

        // Token2 still valid
        assertNotNull(secretManager.verifyToken(token2));
    }

    @Test
    public void testTokenRoundTripEncoding() throws Exception {
        Token<RangerDelegationTokenIdentifier> token = secretManager.createDelegationToken("testUser", "yarn");

        String encoded = token.encodeToUrlString();
        Token<RangerDelegationTokenIdentifier> decoded = new Token<>();
        decoded.decodeFromUrlString(encoded);

        RangerDelegationTokenIdentifier ident = secretManager.verifyToken(decoded);
        assertEquals("testUser", ident.getOwner().toString());
    }

    @Test
    public void testDbPersistenceOnCreate() throws Exception {
        secretManager.createDelegationToken("testUser", "yarn");

        Mockito.verify(masterKeyDao, Mockito.atLeastOnce()).create(Mockito.any(XXRangerDTMasterKey.class));

        Mockito.verify(tokenDao).create(Mockito.any(XXRangerDelegationToken.class));
    }

    /**
     * Testable subclass that bypasses PropertiesUtil config loading.
     */
    private static class TestableSecretManager extends RangerDelegationTokenSecretManager {
        TestableSecretManager() {
            super();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
