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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * A database of Ranger Admin in Docker. The resource mapping table RMM writes to is created and upgraded by the
 * migration scripts of Ranger Admin, run with the client of the database as the Admin setup runs them.
 */
public final class RangerAdminDatabase implements AutoCloseable {
    public static final String CREATE_TABLE_PATCH = "075-create-metastore_mappings-table.sql";
    public static final String OWNER_PATCH = "077-add-owner-to-x_resource_mapping_diff.sql";

    /** the migration scripts of Ranger Admin, relative to this module */
    private static final Path ADMIN_DB_SCRIPTS = Paths.get("..", "security-admin", "db");

    public enum Type {
        POSTGRES("postgres"),
        MYSQL("mysql");

        private final String scriptsDir;

        private Type(String scriptsDir) {
            this.scriptsDir = scriptsDir;
        }
    }

    private final Type type;
    private final JdbcDatabaseContainer<?> container;

    private RangerAdminDatabase(Type type, JdbcDatabaseContainer<?> container) {
        this.type = type;
        this.container = container;
    }

    /**
     * Starts the database and creates the resource mapping table with all its migrations.
     */
    public static RangerAdminDatabase start(Type type, Network network) throws Exception {
        JdbcDatabaseContainer<?> container = type == Type.POSTGRES
            ? new PostgreSQLContainer<>("postgres:12")
            : new MySQLContainer<>("mysql:8.0");
        if (network != null) {
            container.withNetwork(network);
        }
        container.start();

        RangerAdminDatabase database = new RangerAdminDatabase(type, container);
        database.applyPatch(CREATE_TABLE_PATCH);
        database.applyPatch(OWNER_PATCH);
        return database;
    }

    public Type getType() {
        return type;
    }

    public void applyPatch(String patch) throws Exception {
        Path script = ADMIN_DB_SCRIPTS.resolve(type.scriptsDir).resolve("patches").resolve(patch);
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("No migration script " + script.toAbsolutePath());
        }
        String target = "/tmp/" + patch;
        container.copyFileToContainer(MountableFile.forHostPath(script), target);

        ExecResult result = type == Type.POSTGRES
            ? container.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", container.getUsername(),
                "-d", container.getDatabaseName(), "-f", target)
            : container.execInContainer("sh", "-c", String.format("mysql -u%s -p%s %s < %s",
                container.getUsername(), container.getPassword(), container.getDatabaseName(), target));
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(String.format("%s failed on %s: %s%s",
                patch, type, result.getStdout(), result.getStderr()));
        }
    }

    public String getDriverClassName() {
        return container.getDriverClassName();
    }

    public String getJdbcUrl() {
        return container.getJdbcUrl();
    }

    public String getUsername() {
        return container.getUsername();
    }

    public String getPassword() {
        return container.getPassword();
    }

    public DataSource newDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(getJdbcUrl(), getUsername(), getPassword());
        dataSource.setDriverClassName(getDriverClassName());
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(newDataSource());
    }

    @Override
    public void close() {
        container.close();
    }

    @Override
    public String toString() {
        return type.name();
    }
}
