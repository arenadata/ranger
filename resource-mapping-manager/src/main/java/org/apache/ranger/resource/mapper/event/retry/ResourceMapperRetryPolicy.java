package org.apache.ranger.resource.mapper.event.retry;

import org.apache.hadoop.io.retry.RetryPolicy;

public interface ResourceMapperRetryPolicy {
    RetryPolicy.RetryAction shouldRetry(Exception e, int retries) throws Exception;

    static ResourceMapperRetryPolicy fromHadoopPolicy(RetryPolicy policy) {
        // we don't use last failovers num and isIdempotent flag
        return (exc, retries) -> policy.shouldRetry(exc, retries, 0, false);
    }
}
