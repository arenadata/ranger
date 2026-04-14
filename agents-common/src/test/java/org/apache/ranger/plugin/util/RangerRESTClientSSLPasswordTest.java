/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.plugin.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class RangerRESTClientSSLPasswordTest {

    private static final String KEYSTORE_PASSWORD = "keystorePass123";
    private static final String TRUSTSTORE_PASSWORD = "truststorePass456";

    private File keystoreFile;
    private File truststoreFile;
    private File jceksFile;
    private Configuration config;
    private String jceksPath;

    @Before
    public void setUp() throws Exception {
        keystoreFile = createTempKeyStore(KEYSTORE_PASSWORD);
        truststoreFile = createTempKeyStore(TRUSTSTORE_PASSWORD);

        // Create a temporary JCEKS file with credentials
        jceksFile = File.createTempFile("ranger-test", ".jceks");
        jceksFile.deleteOnExit();

        // Set up Hadoop configuration with the JCEKS provider path
        Configuration hadoopConf = new Configuration();
        jceksPath = "localjceks://file/" + jceksFile.getAbsolutePath();
        hadoopConf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, jceksPath);

        // Store credentials in the JCEKS file
        CredentialProvider provider = CredentialProviderFactory.getProviders(hadoopConf).get(0);
        provider.createCredentialEntry(
                RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_CREDENTIAL_ALIAS,
                KEYSTORE_PASSWORD.toCharArray());
        provider.createCredentialEntry(
                RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_CREDENTIAL_ALIAS,
                TRUSTSTORE_PASSWORD.toCharArray());
        provider.flush();

        config = new Configuration();
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
    }

    @After
    public void tearDown() {
        if (keystoreFile != null && keystoreFile.exists()) {
            keystoreFile.delete();
        }
        if (truststoreFile != null && truststoreFile.exists()) {
            truststoreFile.delete();
        }
        if (jceksFile != null && jceksFile.exists()) {
            jceksFile.delete();
        }
    }

    @Test
    public void testKeyAndTrustStorePasswordsFromConfig() {
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);

        KeyManager[] keyManagers = client.getKeyManagers(keystoreFile.getAbsolutePath(), KEYSTORE_PASSWORD);
        TrustManager[] trustManagers = client.getTrustManagers(truststoreFile.getAbsolutePath(), TRUSTSTORE_PASSWORD);

        assertNotNull("KeyManagers should not be null with correct password", keyManagers);
        assertTrue("KeyManagers should contain at least one entry", keyManagers.length > 0);
        assertNotNull("TrustManagers should not be null with correct password", trustManagers);
        assertTrue("TrustManagers should contain at least one entry", trustManagers.length > 0);
    }

    @Test
    public void testSSLContextCreationWithCorrectPasswords() {
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);
        KeyManager[] km = client.getKeyManagers(keystoreFile.getAbsolutePath(), KEYSTORE_PASSWORD);
        TrustManager[] tm = client.getTrustManagers(truststoreFile.getAbsolutePath(), TRUSTSTORE_PASSWORD);
        SSLContext sslContext = client.getSSLContext(km, tm);

        assertNotNull(sslContext);

        assertNotNull("Client should be built successfully", client.getClient());
    }

    @Test
    public void testKeyStorePasswordIncorrectJCEKSPrecedence() {
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, "wrongPassword");
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_CREDENTIAL, jceksPath);
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, "wrongPassword");
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_CREDENTIAL, jceksPath);

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);

        assertNotNull("Client should be built successfully", client.getClient());
    }

    @Test(expected = IllegalStateException.class)
    public void testKeyStorePasswordIncorrect() {
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, "wrongPassword");
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);

        // Should throw exception
        client.getClient();
    }

    @Test(expected = IllegalStateException.class)
    public void testTrustStorePasswordIncorrect() {
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, "wrongPassword");

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);

        // Should throw exception
        client.getClient();
    }

    @Test
    public void testBuildClientWithCorrectPasswords() {
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);

        assertNotNull("Client should be built successfully", client.getClient());
    }

    @Test
    public void testClientBuiltSuccessfullyEvenWithMissingTrustPassword() {
        // Missing truststore password should not prevent client creation (falls back to default)
        config.set(RangerRESTClient.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        // truststore password not set

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);
        // Should build client without throwing exception
        assertNotNull(client.getClient());
    }

    @Test
    public void testClientBuiltSuccessfullyEvenWithMissingKeystorePassword() {
        // Missing keystore password should not prevent client creation (falls back to default)
        config.set(RangerRESTClient.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);
        // keystore password not set

        RangerRESTClient client = new RangerRESTClient("https://localhost:6182", null, config);
        // Should build client without throwing exception
        assertNotNull(client.getClient());
    }

    /**
     * Creates a temporary JKS keystore containing a single private key entry
     * with a self-signed certificate.
     */
    private File createTempKeyStore(String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password.toCharArray());

        // Generate a key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Generate a self-signed X.509 certificate
        X509Certificate cert = generateSelfSignedCertificate(kp);

        // Store the private key with the certificate chain
        ks.setKeyEntry("alias", kp.getPrivate(), password.toCharArray(),
                new Certificate[]{cert});

        File tempFile = File.createTempFile("test", ".jks");
        tempFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            ks.store(fos, password.toCharArray());
        }
        return tempFile;
    }


    // Minimal self-signed certificate generation (for test purposes only)
    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
        Date currentDate = new Date();
        Date expiryDate = new Date(currentDate.getTime() + 365L * 24L * 60L * 60L * 1000L);

        // Create X509Certificate
        X509v3CertificateBuilder certBuilder =
                new X509v3CertificateBuilder(new X500Name(RFC4519Style.INSTANCE, "CN=Test"), BigInteger.valueOf(30), currentDate, expiryDate,
                        new X500Name(RFC4519Style.INSTANCE, "CN=Test"), SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));
    }
}