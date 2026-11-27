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

package org.apache.ranger.resource.mapper.testing;

import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_DRIVER_CLASSNAME;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_JDBC_URL;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_PASSWORD;
import static org.apache.ranger.resource.mapper.config.ConfigurationKeys.DB_USERNAME;
import static org.apache.ranger.resource.mapper.hive.config.ConfigurationKeys.HMS_FETCH_PERIOD_MS;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ranger.resource.mapper.event.ResourceDiffHandler;
import org.apache.ranger.resource.mapper.hive.HiveResourceMappingManager;
import org.apache.ranger.resource.mapper.hive.config.HiveResourceMappingManagerConfig;

/**
 * RMM running in the test JVM against a Hive Metastore and a Ranger Admin database, built as
 * {@link HiveResourceMappingManager#run} builds it. {@code run} itself is not used: it blocks the thread and exits the
 * JVM when the handling fails, e.g. when the containers stop at the end of a test.
 */
public final class ResourceMappingManagerRunner implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final ResourceDiffHandler handler;
    private final ExecutorService executor;

    private ResourceMappingManagerRunner(HikariDataSource dataSource, ResourceDiffHandler handler,
                                         ExecutorService executor) {
        this.dataSource = dataSource;
        this.handler = handler;
        this.executor = executor;
    }

    /**
     * Starts RMM without a full resync: it takes the snapshot of the metastore and then follows its events.
     *
     * @param settings further RMM settings, e.g. the StarRocks catalogs
     */
    public static ResourceMappingManagerRunner start(RangerAdminDatabase database, String metastoreUri,
                                                     Map<String, String> settings) throws Exception {
        HiveResourceMappingManagerConfig config = new HiveResourceMappingManagerConfig();
        config.set(DB_DRIVER_CLASSNAME, database.getDriverClassName());
        config.set(DB_JDBC_URL, database.getJdbcUrl());
        config.set(DB_USERNAME, database.getUsername());
        config.set(DB_PASSWORD, database.getPassword());
        config.set("hive.metastore.uris", metastoreUri);
        config.set(HMS_FETCH_PERIOD_MS, "200");
        settings.forEach(config::set);

        HikariDataSource dataSource = new HikariDataSource(config.getDbConfig());
        ResourceDiffHandler handler = buildEventHandler(config, dataSource);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            handler.start(false);
            return null;
        });
        return new ResourceMappingManagerRunner(dataSource, handler, executor);
    }

    private static ResourceDiffHandler buildEventHandler(HiveResourceMappingManagerConfig config, DataSource dataSource)
        throws Exception {
        Method buildEventHandler = HiveResourceMappingManager.class.getDeclaredMethod(
            "buildEventHandler", HiveResourceMappingManagerConfig.class, DataSource.class);
        buildEventHandler.setAccessible(true);
        return (ResourceDiffHandler) buildEventHandler.invoke(new HiveResourceMappingManager(), config, dataSource);
    }

    @Override
    public void close() throws Exception {
        handler.close();
        executor.shutdownNow();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        dataSource.close();
    }
}
