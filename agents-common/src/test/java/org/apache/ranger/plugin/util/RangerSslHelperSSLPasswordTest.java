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
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.SSLContext;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RangerSslHelperSSLPasswordTest {

    private static final String KEYSTORE_PASSWORD = "keystorePass123";
    private static final String TRUSTSTORE_PASSWORD = "truststorePass456";

    private File keystoreFile;
    private File truststoreFile;
    private File jceksFile;
    private String jceksPath;

    @Before
    public void setUp() throws Exception {
        keystoreFile = createTempKeyStore(KEYSTORE_PASSWORD);
        truststoreFile = createTempKeyStore(TRUSTSTORE_PASSWORD);

        // Create a temporary JCEKS file with credentials
        jceksFile = File.createTempFile("ranger-test", ".jceks");
        jceksFile.deleteOnExit();

        Configuration hadoopConf = new Configuration();
        jceksPath = "localjceks://file/" + jceksFile.getAbsolutePath();
        hadoopConf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, jceksPath);

        CredentialProvider provider = CredentialProviderFactory.getProviders(hadoopConf).get(0);
        provider.createCredentialEntry(
                RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_CREDENTIAL_ALIAS,
                KEYSTORE_PASSWORD.toCharArray());
        provider.createCredentialEntry(
                RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_CREDENTIAL_ALIAS,
                TRUSTSTORE_PASSWORD.toCharArray());
        provider.flush();
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
    public void testSSLContextCreationWithCorrectPasswords() throws Exception {
        Configuration conf = new Configuration(false);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);

        File configFile = writeConfigurationToFile(conf);
        RangerSslHelper helper = new RangerSslHelper(configFile.getAbsolutePath());

        SSLContext sslContext = helper.createContext();
        assertNotNull("SSLContext should be created with correct passwords", sslContext);
        configFile.delete();
    }

    @Test
    public void testSSLContextFailsWithIncorrectTruststorePassword() throws Exception {
        Configuration conf = new Configuration(false);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, "wrongPassword");

        File configFile = writeConfigurationToFile(conf);
        RangerSslHelper helper = new RangerSslHelper(configFile.getAbsolutePath());

        SSLContext sslContext = helper.createContext();
        assertNull("SSLContext should be null when truststore password is incorrect", sslContext);
        configFile.delete();
    }

    @Test
    public void testSSLContextCreatedEvenWithMissingKeystorePassword() throws Exception {
        // Keystore password missing → keystore loading is skipped; truststore alone may succeed.
        Configuration conf = new Configuration(false);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
        // No keystore password
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, TRUSTSTORE_PASSWORD);

        File configFile = writeConfigurationToFile(conf);
        RangerSslHelper helper = new RangerSslHelper(configFile.getAbsolutePath());

        SSLContext sslContext = helper.createContext();
        assertNotNull("SSLContext should be created using only truststore", sslContext);
        configFile.delete();
    }

    @Test
    public void testSSLContextCreatedEvenWithMissingTruststorePassword() throws Exception {
        // Truststore password missing → truststore loading skipped; keystore alone may succeed.
        Configuration conf = new Configuration(false);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, KEYSTORE_PASSWORD);
        // No truststore password

        File configFile = writeConfigurationToFile(conf);
        RangerSslHelper helper = new RangerSslHelper(configFile.getAbsolutePath());

        SSLContext sslContext = helper.createContext();
        assertNull("SSLContext should not be created using only keystore", sslContext);
        configFile.delete();
    }

    @Test
    public void testSSLContextFromJceksCredentials() throws Exception {
        Configuration conf = new Configuration(false);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_CREDENTIAL, jceksPath);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_CREDENTIAL, jceksPath);
        // No direct passwords

        File configFile = writeConfigurationToFile(conf);
        RangerSslHelper helper = new RangerSslHelper(configFile.getAbsolutePath());

        SSLContext sslContext = helper.createContext();
        assertNotNull("SSLContext should be created using JCEKS credentials", sslContext);
        configFile.delete();
    }

    @Test
    public void testJceksPrecedenceOverIncorrectDirectPassword() throws Exception {
        Configuration conf = new Configuration(false);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE, keystoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE, truststoreFile.getAbsolutePath());
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_TYPE, "jks");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_TYPE, "jks");
        // Incorrect direct passwords
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_PASSWORD, "wrongPassword");
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_PASSWORD, "wrongPassword");
        // Correct JCEKS credentials
        conf.set(RangerSslHelper.RANGER_POLICYMGR_CLIENT_KEY_FILE_CREDENTIAL, jceksPath);
        conf.set(RangerSslHelper.RANGER_POLICYMGR_TRUSTSTORE_FILE_CREDENTIAL, jceksPath);

        File configFile = writeConfigurationToFile(conf);
        RangerSslHelper helper = new RangerSslHelper(configFile.getAbsolutePath());

        SSLContext sslContext = helper.createContext();
        assertNotNull("SSLContext should succeed because JCEKS password takes precedence", sslContext);
        configFile.delete();
    }

    // Writes a Configuration object to a temporary Hadoop XML file.
    private File writeConfigurationToFile(Configuration conf) throws Exception {
        File tempFile = File.createTempFile("ranger-ssl", ".xml");
        tempFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            conf.writeXml(fos);
        }
        return tempFile;
    }

    // Creates a temporary JKS keystore with a self‑signed certificate.
    private File createTempKeyStore(String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password.toCharArray());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate cert = generateSelfSignedCertificate(kp);

        ks.setKeyEntry("alias", kp.getPrivate(), password.toCharArray(),
                new Certificate[]{cert});

        File tempFile = File.createTempFile("test", ".jks");
        tempFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            ks.store(fos, password.toCharArray());
        }
        return tempFile;
    }

    // Generates a self‑signed X.509 certificate using Bouncy Castle.
    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
        Date currentDate = new Date();
        Date expiryDate = new Date(currentDate.getTime() + 365L * 24L * 60L * 60L * 1000L);

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                new X500Name(RFC4519Style.INSTANCE, "CN=Test"),
                BigInteger.valueOf(System.currentTimeMillis()),
                currentDate,
                expiryDate,
                new X500Name(RFC4519Style.INSTANCE, "CN=Test"),
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .build(keyPair.getPrivate());

        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));
    }
}