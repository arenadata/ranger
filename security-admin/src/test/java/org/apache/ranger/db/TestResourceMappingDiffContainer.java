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

package org.apache.ranger.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.SharedCacheMode;
import javax.persistence.ValidationMode;
import javax.persistence.spi.ClassTransformer;
import javax.persistence.spi.PersistenceUnitInfo;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;
import org.apache.ranger.biz.ResourceMappingMgr;
import org.apache.ranger.entity.XXResourceMappingDiff;
import org.apache.ranger.plugin.model.ResourceMappingDiff;
import org.eclipse.persistence.jpa.PersistenceProvider;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Ranger Admin reads the owners RMM wrote into the resource mapping table, as the Admin migrations leave it, through
 * its JPA mapping and named queries, and returns them in the resource mappings REST API.
 */
public class TestResourceMappingDiffContainer {
    private static final String[] MIGRATIONS = {
        "075-create-metastore_mappings-table.sql",
        "077-add-owner-to-x_resource_mapping_diff.sql",
    };

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static EntityManagerFactory entityManagerFactory;

    @BeforeClass
    public static void start() throws Exception {
        Assume.assumeTrue("the test needs Docker", DockerClientFactory.instance().isDockerAvailable());

        postgres = new PostgreSQLContainer<>("postgres:12");
        postgres.start();
        for (String migration : MIGRATIONS) {
            migrate(migration);
        }

        DriverManagerDataSource driverManagerDataSource =
            new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        driverManagerDataSource.setDriverClassName(postgres.getDriverClassName());
        dataSource = driverManagerDataSource;

        Map<String, Object> properties = new HashMap<>();
        properties.put("eclipselink.weaving", "false");
        properties.put("eclipselink.logging.level", "WARNING");
        entityManagerFactory = new PersistenceProvider()
            .createContainerEntityManagerFactory(new AdminPersistenceUnit(dataSource), properties);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // the rows as RMM writes them
        insert(jdbc, 101L, "hive.db1.t1", "hdfs://nn:8020/warehouse/db1.db/t1", "alice", null, null, null, "CREATE", "hdfs");
        insert(jdbc, 102L, "hive.db1.t1", "hdfs://nn:8020/warehouse/db1.db/t1", "alice",
            "hive.db1.t1", "hdfs://nn:8020/warehouse/db1.db/t1", "bob", "UPDATE", "hdfs");
        insert(jdbc, 103L, "hive.db1.t2", "hdfs://nn:8020/warehouse/db1.db/t2", null, null, null, null, "CREATE", "hdfs");
        insert(jdbc, 104L, "hive.db1.t1", "sr_hive.db1.t1", "alice", null, null, null, "CREATE", "starrocks");
    }

    @AfterClass
    public static void stop() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    public void testOwnersAreReturned() {
        List<ResourceMappingDiff> diffs = resourceMappingMgr().getAllDiffs("hive", "hdfs").getDiffs();

        assertEquals(3, diffs.size());
        ResourceMappingDiff create = diff(diffs, "CREATE", "hive.db1.t1");
        assertEquals("alice", create.getOldEntity().getOwner());
        assertNull(create.getNewEntity());

        ResourceMappingDiff update = diff(diffs, "UPDATE", "hive.db1.t1");
        assertEquals("alice", update.getOldEntity().getOwner());
        assertEquals("bob", update.getNewEntity().getOwner());
        assertEquals("hdfs://nn:8020/warehouse/db1.db/t1", update.getNewEntity().getLocation());
    }

    /** a row of a table that a role owns, or written before RMM published owners */
    @Test
    public void testMappingWithoutOwner() {
        List<ResourceMappingDiff> diffs = resourceMappingMgr().getAllDiffs("hive", "hdfs").getDiffs();

        assertNull(diff(diffs, "CREATE", "hive.db1.t2").getOldEntity().getOwner());
    }

    @Test
    public void testNewerDiffsOfAnotherTarget() {
        List<ResourceMappingDiff> diffs = resourceMappingMgr().getDiffsNewerThan("hive", "starrocks", 0L).getDiffs();

        assertEquals(1, diffs.size());
        assertEquals("sr_hive.db1.t1", diffs.get(0).getOldEntity().getLocation());
        assertEquals("alice", diffs.get(0).getOldEntity().getOwner());
    }

    private static ResourceMappingMgr resourceMappingMgr() {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        RangerDaoManager daoManager = new RangerDaoManager() {
            @Override
            public EntityManager getEntityManager() {
                return entityManager;
            }
        };
        return new ResourceMappingMgr(daoManager);
    }

    private static ResourceMappingDiff diff(List<ResourceMappingDiff> diffs, String diffType, String name) {
        return diffs.stream()
            .filter(diff -> diff.getDiffType().equals(diffType) && diff.getOldEntity().getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No " + diffType + " of " + name + " in " + diffs));
    }

    private static void insert(JdbcTemplate jdbc, long externalId, String oldName, String oldLocation, String oldOwner,
                               String newName, String newLocation, String newOwner, String diffType, String targetService) {
        jdbc.update("INSERT INTO x_resource_mapping_diff(external_id, old_name, old_location, old_owner, new_name,"
                + " new_location, new_owner, entity_type, diff_type, source_service, target_service)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'TABLE', ?, 'hive', ?)",
            externalId, oldName, oldLocation, oldOwner, newName, newLocation, newOwner, diffType, targetService);
    }

    private static void migrate(String migration) throws Exception {
        Path script = Paths.get("db", "postgres", "patches", migration);
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("No migration " + script.toAbsolutePath());
        }
        String target = "/tmp/" + migration;
        postgres.copyFileToContainer(MountableFile.forHostPath(script), target);
        ExecResult result = postgres.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
            "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-f", target);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(migration + " failed: " + result.getStdout() + result.getStderr());
        }
    }

    /**
     * The persistence unit of Ranger Admin as Spring builds it: the entities are found in the classes of the Admin,
     * the named queries come from its mapping file.
     */
    private static final class AdminPersistenceUnit implements PersistenceUnitInfo {
        private final DataSource dataSource;

        public AdminPersistenceUnit(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public String getPersistenceUnitName() {
            return "defaultPU";
        }

        @Override
        public String getPersistenceProviderClassName() {
            return PersistenceProvider.class.getName();
        }

        @Override
        public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }

        @Override
        public DataSource getJtaDataSource() {
            return null;
        }

        @Override
        public DataSource getNonJtaDataSource() {
            return dataSource;
        }

        @Override
        public List<String> getMappingFileNames() {
            return Collections.singletonList("META-INF/jpa_named_queries.xml");
        }

        @Override
        public List<URL> getJarFileUrls() {
            return Collections.emptyList();
        }

        @Override
        public URL getPersistenceUnitRootUrl() {
            return XXResourceMappingDiff.class.getProtectionDomain().getCodeSource().getLocation();
        }

        @Override
        public List<String> getManagedClassNames() {
            return Collections.emptyList();
        }

        @Override
        public boolean excludeUnlistedClasses() {
            return false;
        }

        @Override
        public SharedCacheMode getSharedCacheMode() {
            return SharedCacheMode.UNSPECIFIED;
        }

        @Override
        public ValidationMode getValidationMode() {
            return ValidationMode.NONE;
        }

        @Override
        public Properties getProperties() {
            return new Properties();
        }

        @Override
        public String getPersistenceXMLSchemaVersion() {
            return "2.0";
        }

        @Override
        public ClassLoader getClassLoader() {
            return Thread.currentThread().getContextClassLoader();
        }

        @Override
        public void addTransformer(ClassTransformer transformer) {
            // no weaving in the test
        }

        @Override
        public ClassLoader getNewTempClassLoader() {
            return getClassLoader();
        }
    }
}
