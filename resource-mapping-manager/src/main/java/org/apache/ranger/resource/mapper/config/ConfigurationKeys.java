package org.apache.ranger.resource.mapper.config;

import org.apache.ranger.resource.mapper.event.retry.RetryStrategy;

public class ConfigurationKeys {
    public static final String DB_DRIVER_CLASSNAME = "ranger.rmm.db.driver.classname";
    public static final String DB_JDBC_URL = "ranger.rmm.db.jdbc.url";
    public static final String DB_USERNAME = "ranger.rmm.db.username";
    public static final String DB_PASSWORD = "ranger.rmm.db.password";

    public static final String DB_POOL_MAX_SIZE = "ranger.rmm.db.pool.size.max";
    public static final int DB_POOL_MAX_SIZE_DEFAULT = 10;

    public static final String DB_POOL_MIN_SIZE = "ranger.rmm.db.pool.size.min";
    public static final int DB_POOL_MIN_SIZE_DEFAULT = 2;

    public static final String DB_POOL_IDLE_TIMEOUT = "ranger.rmm.db.pool.idle.timeout";
    public static final long DB_POOL_IDLE_TIMEOUT_DEFAULT = 300000L;

    public static final String DB_POOL_MAX_LIFETIME = "ranger.rmm.db.pool.lifetime.max";
    public static final long DB_POOL_MAX_LIFETIME_DEFAULT = 1800000L;

    public static final String DB_POOL_CONNECTION_TIMEOUT = "ranger.rmm.db.pool.connection.timeout";
    public static final long DB_POOL_CONNECTION_TIMEOUT_DEFAULT = 30000;

    public static final String EVENT_APPLIER_RETRY_STRATEGY = "ranger.rmm.event.applier.retry.strategy";
    public static final RetryStrategy EVENT_APPLIER_RETRY_STRATEGY_DEFAULT = RetryStrategy.FIXED_SLEEP;

    public static final String EVENT_APPLIER_RETRY_INTERVAL_MS = "ranger.rmm.event.applier.retry.interval.ms";
    public static final long EVENT_APPLIER_RETRY_INTERVAL_MS_DEFAULT = 1000L;

    public static final String EVENT_APPLIER_MAX_RETRIES = "ranger.rmm.event.applier.retry.max";
    public static final int EVENT_APPLIER_MAX_RETRIES_DEFAULT = 10;

    public static final String RMM_KRB_PRINCIPAL = "ranger.rmm.kerberos.principal";
    public static final String RMM_KRB_KEYTAB_PATH = "ranger.rmm.kerberos.keytab";
}
