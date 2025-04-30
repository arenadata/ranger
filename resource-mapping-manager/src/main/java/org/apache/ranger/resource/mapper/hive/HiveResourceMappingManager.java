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

package org.apache.ranger.resource.mapper.hive;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.messaging.MessageFactory;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.resource.mapper.config.ResourceMappingManagerConfig;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDaoImpl;
import org.apache.ranger.resource.mapper.event.DbResourceDiffApplier;
import org.apache.ranger.resource.mapper.event.MetastoreEventHandler;
import org.apache.ranger.resource.mapper.event.ResourceDiffSource;
import org.apache.ranger.resource.mapper.event.retry.PolicyBasedRetrySupport;
import org.apache.ranger.resource.mapper.event.retry.ResourceMapperRetryPolicy;
import org.apache.ranger.resource.mapper.event.retry.RetryStrategy;
import org.apache.ranger.resource.mapper.event.retry.RetrySupport;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.auth.KerberosHiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.config.HiveResourceMappingManagerConfig;
import org.apache.ranger.resource.mapper.hive.event.HiveMetastoreEventFetcher;

@Slf4j
public class HiveResourceMappingManager {
    public void run(HiveResourceMappingManagerConfig config) {
        try (MetastoreEventHandler metastoreEventHandler = buildEventHandler(config);) {
            metastoreEventHandler.start();
        } catch (Exception exception) {
            log.error("Error handling events from HMS", exception);
            System.exit(1);
        }
    }

    private MetastoreEventHandler buildEventHandler(HiveResourceMappingManagerConfig config) throws Exception {
        ResourceMappingDiffDao diffDao = buildEntityDao(config);

        return new MetastoreEventHandler(
            buildEventFetcher(config),
            new DbResourceDiffApplier(diffDao, buildEventApplierRetrySupport(config)),
            diffDao
        );
    }

    private ResourceDiffSource buildEventFetcher(HiveResourceMappingManagerConfig config) throws Exception {
        return HiveMetastoreEventFetcher.builder()
            .metaStoreClient(new HiveMetaStoreClient(config))
            .eventMessageDeserializer(MessageFactory.getDefaultInstance(config).getDeserializer())
            .executor(Executors.newSingleThreadScheduledExecutor())
            .fetchPeriodMs(config.getFetchPeriodMs())
            .eventBatchSize(config.getFetchBatchSize())
            .retrySupport(buildHiveListenerRetrySupport(config))
            .authenticator(buildHiveAuthenticator(config))
            .build();
    }

    private ResourceMappingDiffDao buildEntityDao(HiveResourceMappingManagerConfig config) {
        HikariDataSource hikariDataSource = new HikariDataSource(config.getDbConfig());
        return new ResourceMappingDiffDaoImpl(hikariDataSource);
    }

    private RetrySupport buildHiveListenerRetrySupport(HiveResourceMappingManagerConfig config) {
        ResourceMapperRetryPolicy retryPolicy = buildRetryPolicy(
            config.getHiveListenerRetryStrategy(),
            config.getHiveListenerMaxRetries(),
            config.getHiveListenerRetryIntervalMs()
        );
        return new PolicyBasedRetrySupport(retryPolicy, Thread::sleep);
    }

    private RetrySupport buildEventApplierRetrySupport(ResourceMappingManagerConfig config) {
        ResourceMapperRetryPolicy retryPolicy = buildRetryPolicy(
            config.getEventApplierRetryStrategy(),
            config.getEventApplierMaxRetries(),
            config.getEventApplierRetryIntervalMs()
        );
        return new PolicyBasedRetrySupport(retryPolicy, Thread::sleep);
    }

    private HiveAuthenticator buildHiveAuthenticator(HiveResourceMappingManagerConfig config) {
        if (SecurityUtil.getAuthenticationMethod(config) == UserGroupInformation.AuthenticationMethod.KERBEROS) {
            return new KerberosHiveAuthenticator(
                config.getKerberosPrincipal(),
                config.getKerberosKeytabPath()
            );
        }

        return HiveAuthenticator.noOpAuthenticator();
    }

    private ResourceMapperRetryPolicy buildRetryPolicy(RetryStrategy retryStrategy,
                                                       int maxRetries, long retryIntervalMs) {
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
                return ResourceMapperRetryPolicy.fromHadoopPolicy(
                    RetryPolicies.TRY_ONCE_THEN_FAIL
                );
        }
    }
}
