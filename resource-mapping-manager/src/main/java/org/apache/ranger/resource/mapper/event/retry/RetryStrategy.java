package org.apache.ranger.resource.mapper.event.retry;

public enum RetryStrategy {
    FAIL,
    FIXED_SLEEP,
    EXPONENTIAL
}
