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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.ranger.resource.mapper.utils.ThrowingRunnable;
import org.junit.jupiter.api.Test;

class BackoffRetrySupportTest {

    private static final long BASE_MS = 100L;
    private static final long MAX_MS = 1000L;

    // When the action works from the first time, we run it one time and we do not wait.
    @Test
    public void testRunsOnceWhenActionSucceeds() throws RetryException {
        RecordingSleeper sleeper = new RecordingSleeper();
        BackoffRetrySupport retries = new BackoffRetrySupport(BASE_MS, MAX_MS, sleeper);

        FailingAction action = new FailingAction(0);
        retries.withRetries(action);

        assertEquals(1, action.attempts);
        assertTrue(sleeper.delays.isEmpty());
    }

    // When the action fails some times and then works, we retry until it works.
    @Test
    public void testRetriesUntilActionSucceeds() throws RetryException {
        RecordingSleeper sleeper = new RecordingSleeper();
        BackoffRetrySupport retries = new BackoffRetrySupport(BASE_MS, MAX_MS, sleeper);

        FailingAction action = new FailingAction(5);
        retries.withRetries(action);

        assertEquals(6, action.attempts);
        assertEquals(5, sleeper.delays.size());
    }

    // The helper never gives up: even after many failures it still retries (a fixed-limit
    // retry would already throw here). This is the point of the fix for ADPS-1428.
    @Test
    public void testNeverGivesUp() throws RetryException {
        RecordingSleeper sleeper = new RecordingSleeper();
        BackoffRetrySupport retries = new BackoffRetrySupport(BASE_MS, MAX_MS, sleeper);

        FailingAction action = new FailingAction(100);
        retries.withRetries(action);

        assertEquals(101, action.attempts);
        assertEquals(100, sleeper.delays.size());
    }

    // The wait time grows after each failure and never becomes bigger than the maximum.
    // With jitter every wait must be between half of the cap and the full cap.
    @Test
    public void testDelayGrowsAndIsCapped() throws RetryException {
        RecordingSleeper sleeper = new RecordingSleeper();
        BackoffRetrySupport retries = new BackoffRetrySupport(BASE_MS, MAX_MS, sleeper);

        retries.withRetries(new FailingAction(6));

        assertEquals(6, sleeper.delays.size());
        for (int attempt = 0; attempt < sleeper.delays.size(); attempt++) {
            long cap = expectedCap(attempt);
            long delay = sleeper.delays.get(attempt);
            assertTrue(delay >= cap / 2, "delay " + delay + " is below half of cap " + cap);
            assertTrue(delay <= cap, "delay " + delay + " is above cap " + cap);
        }
        // last waits must already sit at the maximum cap
        assertEquals(MAX_MS, expectedCap(4));
        assertEquals(MAX_MS, expectedCap(5));
    }

    // If the thread is interrupted while we wait, we stop, throw RetryException and keep
    // the interrupt flag set, so the caller (for example on shutdown) can also see it.
    @Test
    public void testInterruptDuringWaitStopsRetrying() {
        RetrySleeper interruptingSleeper = durationMs -> {
            throw new InterruptedException();
        };
        BackoffRetrySupport retries = new BackoffRetrySupport(BASE_MS, MAX_MS, interruptingSleeper);

        assertThrows(RetryException.class, () -> retries.withRetries(new FailingAction(1)));
        assertTrue(Thread.interrupted(), "the interrupt flag must be set again before throwing");
    }

    // Bad config values (negative, zero, or max below base) must not crash the helper.
    // They are fixed to safe values in the constructor, so every wait time is still valid.
    @Test
    public void testInvalidConfigIsFixedAndDoesNotCrash() throws RetryException {
        RecordingSleeper sleeper = new RecordingSleeper();
        BackoffRetrySupport retries = new BackoffRetrySupport(-5L, -1L, sleeper);

        retries.withRetries(new FailingAction(3));

        assertEquals(3, sleeper.delays.size());
        for (long delay : sleeper.delays) {
            assertTrue(delay >= 0, "wait time must not be negative: " + delay);
        }
    }

    // Same growth rule as in BackoffRetrySupport: double the base for each attempt, but not
    // bigger than the maximum.
    private long expectedCap(int attempt) {
        long delay = BASE_MS;
        for (int i = 0; i < attempt && delay < MAX_MS; i++) {
            delay <<= 1;
        }
        return Math.min(delay, MAX_MS);
    }

    private static class RecordingSleeper implements RetrySleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void sleepForMs(long durationMs) {
            delays.add(durationMs);
        }
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
