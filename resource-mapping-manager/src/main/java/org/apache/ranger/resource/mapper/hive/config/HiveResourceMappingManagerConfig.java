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

package org.apache.ranger.resource.mapper.hive.config;

import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FETCH_BATCH_SIZE;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FETCH_BATCH_SIZE_DEFAULT;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FETCH_PERIOD_MS;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FETCH_PERIOD_MS_DEFAULT;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FULL_SYNC;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FULL_SYNC_DEFAULT;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_MAX_RETRIES;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_MAX_RETRIES_DEFAULT;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_RETRY_INTERVAL_MS;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_RETRY_INTERVAL_MS_DEFAULT;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_RETRY_STRATEGY;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_RETRY_STRATEGY_DEFAULT;

import org.apache.ranger.resource.mapper.config.ResourceMappingManagerConfig;
import org.apache.ranger.resource.mapper.event.retry.RetryStrategy;

public class HiveResourceMappingManagerConfig extends ResourceMappingManagerConfig {
    private static final String HIVE_CONFIG_FILE = "hive-site.xml";

    public HiveResourceMappingManagerConfig() {
        addResource(HIVE_CONFIG_FILE);
    }

    public long getFetchPeriodMs() {
        return getLong(HMS_FETCH_PERIOD_MS, HMS_FETCH_PERIOD_MS_DEFAULT);
    }

    public int getFetchBatchSize() {
        return getInt(HMS_FETCH_BATCH_SIZE, HMS_FETCH_BATCH_SIZE_DEFAULT);
    }

    public RetryStrategy getHiveListenerRetryStrategy() {
        return getEnum(HMS_RETRY_STRATEGY, HMS_RETRY_STRATEGY_DEFAULT);
    }

    public long getHiveListenerRetryIntervalMs() {
        return getLong(HMS_RETRY_INTERVAL_MS, HMS_RETRY_INTERVAL_MS_DEFAULT);
    }

    public int getHiveListenerMaxRetries() {
        return getInt(HMS_MAX_RETRIES, HMS_MAX_RETRIES_DEFAULT);
    }

    public boolean isFullMetastoreSync() {
        return getBoolean(HMS_FULL_SYNC, HMS_FULL_SYNC_DEFAULT);
    }
}
