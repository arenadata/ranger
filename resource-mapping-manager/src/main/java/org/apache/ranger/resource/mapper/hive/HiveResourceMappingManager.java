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
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.messaging.MessageFactory;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.resource.mapper.config.ResourceMappingManagerConfig;
import org.apache.ranger.resource.mapper.dao.DefaultResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.dao.ResourceMappingDiffDao;
import org.apache.ranger.resource.mapper.event.DbResourceDiffCollector;
import org.apache.ranger.resource.mapper.event.ResourceDiffCollector;
import org.apache.ranger.resource.mapper.event.ResourceDiffHandler;
import org.apache.ranger.resource.mapper.event.ResourceDiffSource;
import org.apache.ranger.resource.mapper.event.retry.BackoffRetrySupport;
import org.apache.ranger.resource.mapper.event.retry.PolicyBasedRetrySupport;
import org.apache.ranger.resource.mapper.event.retry.ResourceMapperRetryPolicy;
import org.apache.ranger.resource.mapper.event.retry.RetryPolicyFactory;
import org.apache.ranger.resource.mapper.event.retry.RetrySupport;
import org.apache.ranger.resource.mapper.hive.auth.HiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.auth.KerberosHiveAuthenticator;
import org.apache.ranger.resource.mapper.hive.config.HiveResourceMappingManagerConfig;
import org.apache.ranger.resource.mapper.hive.event.CompositeHiveResourceDiffCollector;
import org.apache.ranger.resource.mapper.hive.event.DbHiveIntermediateEventsResolver;
import org.apache.ranger.resource.mapper.hive.event.fetch.CompositeHiveMetastoreFetcher;
import org.apache.ranger.resource.mapper.hive.event.fetch.HiveMetastoreEventFetcher;
import org.apache.ranger.resource.mapper.hive.event.fetch.HiveMetastoreSnapshotFetcher;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Slf4j
public class HiveResourceMappingManager {
    public void run(HiveResourceMappingManagerConfig config) {
        try (HikariDataSource dataSource = new HikariDataSource(config.getDbConfig());
             ResourceDiffHandler resourceDiffHandler = buildEventHandler(config, dataSource)) {
            resourceDiffHandler.start(config.isFullMetastoreSync());
        } catch (HiveStartUpException exception) {
            log.warn("Failed to start event fetching from HMS", exception);
            // todo add dynamic Hive config loading mechanism instead of parking
            LockSupport.park();
        } catch (Exception exception) {
            log.error("Error handling events from HMS", exception);
            System.exit(1);
        }
    }

    private ResourceDiffHandler buildEventHandler(HiveResourceMappingManagerConfig config, DataSource dataSource) {
        ResourceMappingDiffDao diffDao = new DefaultResourceMappingDiffDao(dataSource);
        RetryPolicyFactory retryPolicyFactory = new RetryPolicyFactory();

        return new ResourceDiffHandler(
            buildEventFetcher(retryPolicyFactory, config),
            buildResourceDiffCollector(retryPolicyFactory, config, diffDao, dataSource),
            diffDao
        );
    }

    private ResourceDiffCollector buildResourceDiffCollector(RetryPolicyFactory retryPolicyFactory,
                                                             HiveResourceMappingManagerConfig config,
                                                             ResourceMappingDiffDao diffDao,
                                                             DataSource dataSource) {
        RetrySupport retrySupport = buildEventApplierRetrySupport(retryPolicyFactory, config);

        return CompositeHiveResourceDiffCollector.builder()
            .delegate(new DbResourceDiffCollector(diffDao, retrySupport))
            .intermediateEventsResolver(new DbHiveIntermediateEventsResolver(diffDao))
            .transactionManager(new JdbcTransactionManager(dataSource))
            .retrySupport(retrySupport)
            .build();
    }

    private ResourceDiffSource buildEventFetcher(RetryPolicyFactory retryPolicyFactory,
                                                 HiveResourceMappingManagerConfig config) {
        try {
            HiveAuthenticator hiveAuthenticator = buildHiveAuthenticator(config);
            // login is needed for HiveMetaStoreClient
            hiveAuthenticator.login();

            HiveMetaStoreClient hiveMetaStoreClient = new HiveMetaStoreClient(config);
            RetrySupport retrySupport = buildHiveListenerRetrySupport(retryPolicyFactory, config);

            HiveMetastoreSnapshotFetcher snapshotFetcher =
                buildSnapshotEventFetcher(hiveMetaStoreClient, retrySupport, hiveAuthenticator, config);

            RetrySupport eventFetcherRetrySupport = buildHiveListenerBackoffRetrySupport(config);
            HiveMetastoreEventFetcher eventFetcher = buildHiveMetastoreEventFetcher(
                hiveMetaStoreClient, eventFetcherRetrySupport, hiveAuthenticator, config);

            return CompositeHiveMetastoreFetcher.builder()
                .metaStoreClient(hiveMetaStoreClient)
                .snapshotFetcher(snapshotFetcher)
                .eventFetcher(eventFetcher)
                .executor(Executors.newSingleThreadExecutor())
                .authenticator(hiveAuthenticator)
                .eventBatchSize(config.getFetchBatchSize())
                .build();
        } catch (Exception e) {
            throw new HiveStartUpException(e);
        }
    }

    private HiveMetastoreEventFetcher buildHiveMetastoreEventFetcher(
        IMetaStoreClient metaStoreClient,
        RetrySupport retrySupport,
        HiveAuthenticator hiveAuthenticator,
        HiveResourceMappingManagerConfig config
    ) {
        return HiveMetastoreEventFetcher.builder()
            .metaStoreClient(metaStoreClient)
            .eventMessageDeserializer(MessageFactory.getDefaultInstance(config).getDeserializer())
            .executor(Executors.newSingleThreadScheduledExecutor())
            .authenticator(hiveAuthenticator)
            .retrySupport(retrySupport)
            .fetchPeriodMs(config.getFetchPeriodMs())
            .eventBatchSize(config.getFetchBatchSize())
            .build();
    }

    private HiveMetastoreSnapshotFetcher buildSnapshotEventFetcher(
        IMetaStoreClient metaStoreClient,
        RetrySupport retrySupport,
        HiveAuthenticator hiveAuthenticator,
        HiveResourceMappingManagerConfig config) {

        return HiveMetastoreSnapshotFetcher.builder()
            .metaStoreClient(metaStoreClient)
            .executor(Executors.newSingleThreadExecutor())
            .authenticator(hiveAuthenticator)
            .retrySupport(retrySupport)
            .eventBatchSize(config.getFetchBatchSize())
            .build();
    }

    private RetrySupport buildHiveListenerRetrySupport(
        RetryPolicyFactory retryPolicyFactory,
        HiveResourceMappingManagerConfig config) {
        ResourceMapperRetryPolicy retryPolicy = retryPolicyFactory.provide(
            config.getHiveListenerRetryStrategy(),
            config.getHiveListenerMaxRetries(),
            config.getHiveListenerRetryIntervalMs()
        );
        return new PolicyBasedRetrySupport(retryPolicy, Thread::sleep);
    }

    private RetrySupport buildHiveListenerBackoffRetrySupport(HiveResourceMappingManagerConfig config) {
        return new BackoffRetrySupport(
            config.getHmsReconnectBaseIntervalMs(),
            config.getHmsReconnectMaxIntervalMs(),
            Thread::sleep);
    }

    private RetrySupport buildEventApplierRetrySupport(
        RetryPolicyFactory retryPolicyFactory,
        ResourceMappingManagerConfig config) {
        ResourceMapperRetryPolicy retryPolicy = retryPolicyFactory.provide(
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
                config.getKerberosKeytabPath(),
                config
            );
        }

        return HiveAuthenticator.noOpAuthenticator();
    }
}
