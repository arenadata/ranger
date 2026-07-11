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

import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.utils.ThrowingRunnable;

/**
 * Retry helper that never gives up. It runs the action and, if it fails, waits and tries
 * again until the action works or the thread is interrupted. The wait grows after each
 * failure (exponential backoff), but it is capped at a maximum, and a small random jitter
 * is added so many clients do not retry at the same moment.
 */
@Slf4j
public class BackoffRetrySupport implements RetrySupport {

    private final long baseIntervalMs;
    private final long maxIntervalMs;
    private final RetrySleeper retrySleeper;

    /**
     * The config values are already checked in the config class. Here we only keep a safe floor
     * (base at least 1 ms, max not smaller than base), so this helper can never break on a wrong
     * wait time, even if it is created with bad values from some other place.
     *
     * @param baseIntervalMs wait time for the first retry, in milliseconds
     * @param maxIntervalMs  the biggest possible wait time, in milliseconds
     * @param retrySleeper   how to wait between the tries (usually {@code Thread::sleep})
     */
    public BackoffRetrySupport(long baseIntervalMs, long maxIntervalMs, RetrySleeper retrySleeper) {
        this.baseIntervalMs = Math.max(1, baseIntervalMs);
        this.maxIntervalMs = Math.max(this.baseIntervalMs, maxIntervalMs);
        this.retrySleeper = retrySleeper;
    }

    /**
     * Runs the action. If the action throws, it waits (see {@link #backoffWithJitterMs(int)})
     * and runs the action one more time. It repeats this forever until the action does not throw.
     *
     * @param action the work to run; it can throw any exception
     * @throws RetryException only when the thread is interrupted during the wait. Before it throws,
     *                        it sets the interrupt flag again, so the caller can also see the interrupt.
     */
    @Override
    public void withRetries(ThrowingRunnable<?> action) throws RetryException {
        int attempt = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                long delayMs = backoffWithJitterMs(attempt++);
                log.warn("Attempt failed, retrying in {} ms", delayMs, e);
                try {
                    retrySleeper.sleepForMs(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetryException("Interrupted while backing off before retry", interrupted);
                }
            }
        }
    }

    /**
     * Calculates how long to wait before the next try.
     *
     * <p>The base value is doubled one time for every attempt (base, base*2, base*4 ...), but the
     * result is never bigger than the maximum. After that a random jitter is added: the returned
     * value is between the half of the capped value and the full capped value.</p>
     *
     * @param attempt how many times we already failed (the first call uses 0)
     * @return the wait time in milliseconds
     */
    private long backoffWithJitterMs(int attempt) {
        long delay = baseIntervalMs;
        for (int i = 0; i < attempt && delay < maxIntervalMs; i++) {
            delay <<= 1;
        }
        long capped = Math.min(delay, maxIntervalMs);
        long half = capped / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }
}
