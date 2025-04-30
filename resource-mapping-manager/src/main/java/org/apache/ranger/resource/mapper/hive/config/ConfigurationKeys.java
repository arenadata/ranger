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
