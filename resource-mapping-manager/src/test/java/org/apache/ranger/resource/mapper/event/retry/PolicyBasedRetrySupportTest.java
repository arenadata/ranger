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

package org.apache.ranger.resource.mapper.event.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import lombok.RequiredArgsConstructor;
import org.apache.ranger.resource.mapper.utils.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyBasedRetrySupportTest {

    private RetryPolicyFactory retryPolicyFactory;

    @BeforeEach
    public void setUp() {
        retryPolicyFactory = new RetryPolicyFactory();
    }

    @Test
    public void testFixedSleepRetry() throws RetryException {
        testRetry(RetryStrategy.FIXED_SLEEP);
    }

    @Test
    public void testExponentialBackoffRetry() throws RetryException {
        testRetry(RetryStrategy.EXPONENTIAL);
    }

    @Test
    public void testNoRetry() throws RetryException {
        ResourceMapperRetryPolicy retryPolicy = retryPolicyFactory.provide(
            RetryStrategy.FAIL, -1, -1);

        PolicyBasedRetrySupport retries = new PolicyBasedRetrySupport(retryPolicy, this::noOpSleep);
        FailingAction noRetryAction = new FailingAction(0);
        retries.withRetries(noRetryAction);
        assertEquals(1, noRetryAction.attempts);

        FailingAction throwingAction = new FailingAction(1);
        assertThrows(RetryException.class, () -> retries.withRetries(throwingAction));
        assertEquals(1, throwingAction.attempts);
    }

    private void testRetry(RetryStrategy strategy) throws RetryException {
        ResourceMapperRetryPolicy retryPolicy = retryPolicyFactory.provide(
            strategy, 10, 100);
        PolicyBasedRetrySupport retries = new PolicyBasedRetrySupport(retryPolicy, this::noOpSleep);

        FailingAction noRetryAction = new FailingAction(0);
        retries.withRetries(noRetryAction);
        assertEquals(1, noRetryAction.attempts);

        FailingAction action = new FailingAction(5);
        retries.withRetries(action);
        assertEquals(6, action.attempts);

        FailingAction throwingAction = new FailingAction(11);
        assertThrows(RetryException.class, () -> retries.withRetries(throwingAction));
        assertEquals(11, throwingAction.attempts);
    }

    private void noOpSleep(long duration) {
        // do nothing
    }

    @RequiredArgsConstructor
    private static class FailingAction implements ThrowingRunnable<Exception> {

        private final int failsBeforeRun;
        private int attempts;

        @Override
        public void run() throws Exception {
            if (++attempts <= failsBeforeRun) {
                throw new Exception();
            }
        }
    }
}