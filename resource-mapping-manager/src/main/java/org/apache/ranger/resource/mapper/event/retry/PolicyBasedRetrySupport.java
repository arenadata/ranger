package org.apache.ranger.resource.mapper.event.retry;

import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.ranger.resource.mapper.utils.ThrowingRunnable;

public class PolicyBasedRetrySupport implements RetrySupport {
    private final ResourceMapperRetryPolicy retryPolicy;
    private final RetrySleeper retrySleeper;

    public PolicyBasedRetrySupport(ResourceMapperRetryPolicy retryPolicy, RetrySleeper retrySleeper) {
        this.retryPolicy = retryPolicy;
        this.retrySleeper = retrySleeper;
    }

    private <E extends Exception> void retry(
        ThrowingRunnable<E> action,
        Exception exception
    ) throws RetryException {
        boolean actionExecuted = false;
        RetryPolicy.RetryAction retryAction;

        for (int retryNum = 0; !actionExecuted; ++retryNum) {
            try {
                retryAction = retryPolicy.shouldRetry(exception, retryNum);
            } catch (Exception exc) {
                throw new RetryException("Error determining if we should retry acton", exception);
            }

            if (retryAction.action == RetryPolicy.RetryAction.RetryDecision.FAIL) {
                throw new RetryException(retryAction.reason, exception);
            }

            try {
                if (retryAction.delayMillis > 0) {
                    retrySleeper.sleepForMs(retryAction.delayMillis);
                }
            } catch (InterruptedException e) {
                // do nothing
            }

            try {
                action.run();
                actionExecuted = true;
            } catch (Exception e) {
                // do nothing
            }
        }
    }

    @Override
    public void withRetries(ThrowingRunnable<?> action) throws RetryException {
        try {
            action.run();
        } catch (Exception e) {
            retry(action, e);
        }
    }
}
