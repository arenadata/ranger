/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.audit.provider;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

public class AuditPropertyMaskingUtil {
    private static final Logger LOG = LoggerFactory.getLogger(AuditPropertyMaskingUtil.class);

    public static final String MASKED_VALUE = "******";
    public static final String AUDIT_LOG_MASK_KEY_REGEX_PROP = "xasecure.audit.log.mask.key.regex";
    private static final String[] SENSITIVE_PROPERTY_NAME_TOKENS = {
            "password",
            "passwd",
            "pwd",
            "secret",
            "credential",
            "token",
            "apikey",
            "api_key",
            "accesskey",
            "access_key",
            "privatekey",
            "private_key",
            "passphrase",
            "keytab"
    };
    private static final Pattern URL_CREDENTIALS_PATTERN = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s/@:]+:)([^@\\s/]+)@");

    private AuditPropertyMaskingUtil() {
    }

    public static String getLoggablePropertyValue(String propertyName, String propertyValue, List<Pattern> customSensitivePropertyPatterns) {
        if (propertyValue == null) {
            return null;
        }

        if (isSensitiveProperty(propertyName, customSensitivePropertyPatterns)) {
            return MASKED_VALUE;
        }

        return maskCredentialsInUrl(propertyValue);
    }

    public static List<Pattern> getCustomSensitivePropertyPatterns(Properties props) {
        if (props == null) {
            return null;
        }
        List<Pattern> ret = new ArrayList<Pattern>();
        String regexConfig = MiscUtil.getStringProperty(props, AUDIT_LOG_MASK_KEY_REGEX_PROP);
        if (StringUtils.isBlank(regexConfig)) {
            return ret;
        }
        for (String regex : regexConfig.split(",")) {
            String trimmedRegex = StringUtils.trimToEmpty(regex);

            if (StringUtils.isBlank(trimmedRegex)) {
                continue;
            }

            try {
                ret.add(Pattern.compile(trimmedRegex));
            } catch (Exception exception) {
                LOG.warn("Ignoring invalid regex in " + AUDIT_LOG_MASK_KEY_REGEX_PROP + ": " + trimmedRegex, exception);
            }
        }

        return ret;
    }

    private static boolean isSensitiveProperty(String propertyName, List<Pattern> customSensitivePropertyPatterns) {
        if (StringUtils.isBlank(propertyName)) {
            return false;
        }

        String normalizedName = propertyName.toLowerCase(Locale.ROOT);
        return customSensitivePropertyPatterns != null
                ? customSensitivePropertyPatterns.stream()
                .filter(Objects::nonNull)
                .anyMatch(pattern -> pattern.matcher(propertyName).matches())
                : Arrays.stream(SENSITIVE_PROPERTY_NAME_TOKENS).anyMatch(normalizedName::contains);
    }

    private static String maskCredentialsInUrl(String value) {
        return URL_CREDENTIALS_PATTERN.matcher(value).replaceAll("$1" + MASKED_VALUE + "@");
    }
}
