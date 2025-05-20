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

import java.util.concurrent.TimeUnit;
import org.apache.hadoop.io.retry.RetryPolicies;

public class RetryPolicyFactory {
    public static final ResourceMapperRetryPolicy NO_RETRIES_POLICY =
        ResourceMapperRetryPolicy.fromHadoopPolicy(RetryPolicies.TRY_ONCE_THEN_FAIL);

    public ResourceMapperRetryPolicy provide(
        RetryStrategy retryStrategy,
        int maxRetries,
        long retryIntervalMs
    ) {
        switch (retryStrategy) {
            case FIXED_SLEEP:
                return ResourceMapperRetryPolicy.fromHadoopPolicy(
                    RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                        maxRetries, retryIntervalMs, TimeUnit.MILLISECONDS
                    )
                );
            case EXPONENTIAL:
                return ResourceMapperRetryPolicy.fromHadoopPolicy(
                    RetryPolicies.exponentialBackoffRetry(
                        maxRetries, retryIntervalMs, TimeUnit.MILLISECONDS
                    )
                );
            default:
                return NO_RETRIES_POLICY;
        }
    }
}
