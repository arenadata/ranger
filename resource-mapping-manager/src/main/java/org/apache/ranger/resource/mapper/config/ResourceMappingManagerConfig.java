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

package org.apache.ranger.resource.mapper.config;

import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_DRIVER_CLASSNAME;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_JDBC_URL;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_PASSWORD;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_CONNECTION_TIMEOUT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_CONNECTION_TIMEOUT_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_IDLE_TIMEOUT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_IDLE_TIMEOUT_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_MAX_LIFETIME;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_MAX_LIFETIME_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_MAX_SIZE;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_MAX_SIZE_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_MIN_SIZE;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_POOL_MIN_SIZE_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_USERNAME;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.EVENT_APPLIER_MAX_RETRIES;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.EVENT_APPLIER_MAX_RETRIES_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.EVENT_APPLIER_RETRY_INTERVAL_MS;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.EVENT_APPLIER_RETRY_INTERVAL_MS_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.EVENT_APPLIER_RETRY_STRATEGY;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.EVENT_APPLIER_RETRY_STRATEGY_DEFAULT;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.RMM_KRB_KEYTAB_PATH;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.RMM_KRB_PRINCIPAL;

import com.zaxxer.hikari.HikariConfig;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.resource.mapper.event.retry.RetryStrategy;

public class ResourceMappingManagerConfig extends Configuration {
    private static final String CONFIG_FILE = "ranger-rmm-site.xml";
    private static final String DEFAULT_CONFIG_FILE = "ranger-rmm-default.xml";

    public ResourceMappingManagerConfig() {
        addResource(DEFAULT_CONFIG_FILE);
        addResource(CONFIG_FILE);

        loadSystemProperties();
    }

    public HikariConfig getDbConfig() {
        HikariConfig configuration = new HikariConfig();

        configuration.setDriverClassName(getOrThrow(DB_DRIVER_CLASSNAME));
        configuration.setJdbcUrl(getOrThrow(DB_JDBC_URL));
        configuration.setUsername(getOrThrow(DB_USERNAME));
        configuration.setPassword(getOrThrow(DB_PASSWORD));

        configuration.setMaximumPoolSize(
            getInt(DB_POOL_MAX_SIZE, DB_POOL_MAX_SIZE_DEFAULT)
        );
        configuration.setMinimumIdle(
            getInt(DB_POOL_MIN_SIZE, DB_POOL_MIN_SIZE_DEFAULT)
        );
        configuration.setIdleTimeout(
            getLong(DB_POOL_IDLE_TIMEOUT, DB_POOL_IDLE_TIMEOUT_DEFAULT)
        );
        configuration.setMaxLifetime(
            getLong(DB_POOL_MAX_LIFETIME, DB_POOL_MAX_LIFETIME_DEFAULT)
        );
        configuration.setConnectionTimeout(
            getLong(DB_POOL_CONNECTION_TIMEOUT, DB_POOL_CONNECTION_TIMEOUT_DEFAULT)
        );

        return configuration;
    }

    public RetryStrategy getEventApplierRetryStrategy() {
        return getEnum(EVENT_APPLIER_RETRY_STRATEGY, EVENT_APPLIER_RETRY_STRATEGY_DEFAULT);
    }

    public long getEventApplierRetryIntervalMs() {
        return getLong(EVENT_APPLIER_RETRY_INTERVAL_MS, EVENT_APPLIER_RETRY_INTERVAL_MS_DEFAULT);
    }

    public int getEventApplierMaxRetries() {
        return getInt(EVENT_APPLIER_MAX_RETRIES, EVENT_APPLIER_MAX_RETRIES_DEFAULT);
    }

    public String getKerberosPrincipal() {
        return get(RMM_KRB_PRINCIPAL);
    }

    public String getKerberosKeytabPath() {
        return get(RMM_KRB_KEYTAB_PATH);
    }

    private void loadSystemProperties() {
        for (String propertyName : getProps().stringPropertyNames()) {
            String systemPropertyValue = System.getProperty(propertyName);
            if (systemPropertyValue != null) {
                set(propertyName, systemPropertyValue);
            }
        }
    }

    private String getOrThrow(String propertyName) {
        return Optional.ofNullable(get(propertyName))
            .orElseThrow(() ->
                new IllegalArgumentException("Missing required option: " + propertyName));
    }
}
