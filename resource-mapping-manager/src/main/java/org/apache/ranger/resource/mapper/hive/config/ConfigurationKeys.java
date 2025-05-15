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

import org.apache.ranger.resource.mapper.event.retry.RetryStrategy;

public class ConfigurationKeys {
    public static final String HMS_FETCH_PERIOD_MS = "ranger.rmm.hms.fetch.period.ms";
    public static final long HMS_FETCH_PERIOD_MS_DEFAULT = 10000L;

    public static final String HMS_FETCH_BATCH_SIZE = "ranger.rmm.hms.fetch.batch.size";
    public static final int HMS_FETCH_BATCH_SIZE_DEFAULT = 8192;

    public static final String HMS_RETRY_STRATEGY = "ranger.rmm.hms.retry.strategy";
    public static final RetryStrategy HMS_RETRY_STRATEGY_DEFAULT = RetryStrategy.FIXED_SLEEP;

    public static final String HMS_RETRY_INTERVAL_MS = "ranger.rmm.hms.retry.interval.ms";
    public static final long HMS_RETRY_INTERVAL_MS_DEFAULT = 1000L;

    public static final String HMS_MAX_RETRIES = "ranger.rmm.hms.retry.max";
    public static final int HMS_MAX_RETRIES_DEFAULT = 10;
}
