/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.audit.provider;

import org.junit.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuditProviderFactoryPropertyMaskingTest {
    private static final List<String> LOG_EVENTS = Collections.synchronizedList(new ArrayList<>());

    @Test
    public void testSensitiveAuditPropertiesAreMaskedInLogsCustom() throws Exception {
        Properties props = new Properties();
        props.setProperty("xasecure.audit.is.enabled", "true");
        props.setProperty("xasecure.policymgr.clientssl.truststore.password", "secret123");
        props.setProperty("xasecure.audit.destination.solr.urls", "https://admin:myPass@solr1:8983/solr/ranger_audits");
        props.setProperty(AuditPropertyMaskingUtil.AUDIT_LOG_MASK_KEY_REGEX_PROP, "(?i).*(truststore\\.password|custom\\.authheader).*");
        props.setProperty("xasecure.audit.destination.custom.authheader", "Bearer verySensitiveTokenValue");
        props.setProperty("xasecure.audit.token.cache", "rawTokenValue");

        String logs = testSensitiveAuditPropertiesAreMaskedInLogs(props);

        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.policymgr.clientssl.truststore.password=******"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.destination.solr.urls=https://admin:******@solr1:8983/solr/ranger_audits"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.destination.custom.authheader=******"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.token.cache=rawTokenValue"));
        assertFalse(logs.contains("secret123"));
        assertFalse(logs.contains("myPass"));
        assertFalse(logs.contains("verySensitiveTokenValue"));
    }

    @Test
    public void testSensitiveAuditPropertiesAreMaskedInLogsDefault() throws Exception {
        Properties props = new Properties();
        props.setProperty("xasecure.audit.is.enabled", "true");
        props.setProperty("xasecure.policymgr.clientssl.truststore.password", "secret123");
        props.setProperty("xasecure.audit.token.cache", "secretValue");
        props.setProperty("xasecure.audit.test", "no secret value");

        String logs = testSensitiveAuditPropertiesAreMaskedInLogs(props);

        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.policymgr.clientssl.truststore.password=******"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.token.cache=******"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.test=no secret value"));
        assertFalse(logs.contains("secret123"));
        assertFalse(logs.contains("secretValue"));
    }

    @Test
    public void testSensitiveAuditPropertiesAreMaskedInLogsEmpty() throws Exception {
        Properties props = new Properties();
        props.setProperty("xasecure.audit.is.enabled", "true");
        props.setProperty("xasecure.policymgr.clientssl.truststore.password", "secret123");
        props.setProperty("xasecure.audit.token.cache", "secretValue");
        props.setProperty("xasecure.audit.test", "no secret value");

        props.setProperty(AuditPropertyMaskingUtil.AUDIT_LOG_MASK_KEY_REGEX_PROP, "");

        String logs = testSensitiveAuditPropertiesAreMaskedInLogs(props);

        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.policymgr.clientssl.truststore.password=secret123"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.token.cache=secretValue"));
        assertTrue(logs.contains("AUDIT PROPERTY: xasecure.audit.test=no secret value"));
    }

    private String testSensitiveAuditPropertiesAreMaskedInLogs(Properties props) throws Exception {
        resetSlf4j();
        LOG_EVENTS.clear();
        System.setProperty(LoggerFactory.PROVIDER_PROPERTY_KEY, CapturingSlf4jProvider.class.getName());

        try {
            AuditProviderFactory factory = new AuditProviderFactory();
            factory.init(props, "trino");
            factory.shutdown();
        } finally {
            System.clearProperty(LoggerFactory.PROVIDER_PROPERTY_KEY);
            resetSlf4j();
        }
        return String.join("\n", new ArrayList<>(LOG_EVENTS));
    }

    private static void resetSlf4j() throws Exception {
        Method resetMethod = LoggerFactory.class.getDeclaredMethod("reset");
        resetMethod.setAccessible(true);
        resetMethod.invoke(null);
    }

    public static class CapturingSlf4jProvider implements SLF4JServiceProvider {
        private final ILoggerFactory loggerFactory = new CapturingLoggerFactory();
        private final IMarkerFactory markerFactory = new BasicMarkerFactory();
        private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

        @Override
        public ILoggerFactory getLoggerFactory() {
            return loggerFactory;
        }

        @Override
        public IMarkerFactory getMarkerFactory() {
            return markerFactory;
        }

        @Override
        public MDCAdapter getMDCAdapter() {
            return mdcAdapter;
        }

        @Override
        public String getRequestedApiVersion() {
            return "2.0.13";
        }

        @Override
        public void initialize() {
        }
    }

    private static class CapturingLoggerFactory implements ILoggerFactory {
        private final Map<String, CapturingLogger> loggers = new ConcurrentHashMap<String, CapturingLogger>();

        @Override
        public org.slf4j.Logger getLogger(String name) {
            CapturingLogger logger = loggers.get(name);
            if (logger == null) {
                logger = new CapturingLogger(name);
                loggers.put(name, logger);
            }
            return logger;
        }
    }

    private static class CapturingLogger extends AbstractLogger {
        private static final long serialVersionUID = 1L;

        private CapturingLogger(String name) {
            this.name = name;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return CapturingLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, org.slf4j.Marker marker, String messagePattern,
                                                   Object[] arguments, Throwable throwable) {
            String formattedMessage = MessageFormatter.basicArrayFormat(messagePattern, arguments);

            if (throwable != null) {
                formattedMessage = formattedMessage + " " + throwable.getClass().getName();
            }

            LOG_EVENTS.add(level + " " + name + " - " + formattedMessage);
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isTraceEnabled(org.slf4j.Marker marker) {
            return isTraceEnabled();
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled(org.slf4j.Marker marker) {
            return isDebugEnabled();
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled(org.slf4j.Marker marker) {
            return isInfoEnabled();
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled(org.slf4j.Marker marker) {
            return isWarnEnabled();
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled(org.slf4j.Marker marker) {
            return isErrorEnabled();
        }
    }
}
