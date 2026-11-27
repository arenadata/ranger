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

package org.apache.ranger.resource.mapper.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ranger.resource.mapper.hive.event.MetastoreEntityDiffFactory;
import org.apache.ranger.resource.mapper.hive.model.HiveEntityType;
import org.apache.ranger.resource.mapper.model.ResourceMapping;
import org.apache.ranger.resource.mapper.testing.RangerAdminDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RMM writes the owners of the Hive entities into the resource mapping table of Ranger Admin, as it is after the
 * Admin migrations, on every database these migrations are run against in the tests.
 */
@Testcontainers(disabledWithoutDocker = true)
public class DefaultResourceMappingDiffDaoContainerTest {
    private static final Map<RangerAdminDatabase.Type, RangerAdminDatabase> DATABASES =
        new EnumMap<>(RangerAdminDatabase.Type.class);

    @BeforeAll
    public static void startDatabases() throws Exception {
        for (RangerAdminDatabase.Type type : RangerAdminDatabase.Type.values()) {
            DATABASES.put(type, RangerAdminDatabase.start(type, null));
        }
    }

    @AfterAll
    public static void stopDatabases() {
        DATABASES.values().forEach(RangerAdminDatabase::close);
    }

    /** the Admin setup runs a migration again when it is not recorded as applied */
    @ParameterizedTest
    @EnumSource(RangerAdminDatabase.Type.class)
    public void testOwnerMigrationIsRepeatable(RangerAdminDatabase.Type type) throws Exception {
        RangerAdminDatabase database = DATABASES.get(type);

        database.applyPatch(RangerAdminDatabase.OWNER_PATCH);

        List<String> columns = columns(database.newDataSource());
        assertTrue(columns.contains("old_owner"), columns.toString());
        assertTrue(columns.contains("new_owner"), columns.toString());
    }

    @ParameterizedTest
    @EnumSource(RangerAdminDatabase.Type.class)
    public void testOwnersOfAnUpdateAreStored(RangerAdminDatabase.Type type) {
        RangerAdminDatabase database = DATABASES.get(type);
        String name = uniqueName();
        DefaultResourceMappingDiffDao dao = new DefaultResourceMappingDiffDao(database.newDataSource());

        dao.insertDiff(MetastoreEntityDiffFactory.updateEntity(
            new ResourceMapping(name, "hdfs://nn:8020/warehouse/t1", "alice"),
            new ResourceMapping(name, "hdfs://nn:8020/warehouse/t1", "bob"),
            HiveEntityType.TABLE, 5L));

        Map<String, Object> row = database.jdbc().queryForMap(
            "SELECT old_owner, new_owner, diff_type FROM x_resource_mapping_diff WHERE old_name = ?", name);
        assertEquals("alice", value(row, "old_owner"));
        assertEquals("bob", value(row, "new_owner"));
        assertEquals("UPDATE", value(row, "diff_type"));
    }

    @ParameterizedTest
    @EnumSource(RangerAdminDatabase.Type.class)
    public void testEntityWithoutUserOwner(RangerAdminDatabase.Type type) {
        RangerAdminDatabase database = DATABASES.get(type);
        String name = uniqueName();
        DefaultResourceMappingDiffDao dao = new DefaultResourceMappingDiffDao(database.newDataSource());

        dao.insertDiff(MetastoreEntityDiffFactory.createEntity(
            name, HiveEntityType.TABLE, "hdfs://nn:8020/warehouse/t2", null, 6L));

        Map<String, Object> row = database.jdbc().queryForMap(
            "SELECT old_owner, new_owner FROM x_resource_mapping_diff WHERE old_name = ?", name);
        assertNull(value(row, "old_owner"));
        assertNull(value(row, "new_owner"));
    }

    /** the intermediate events of a snapshot delete a mapping by its name and location, whoever owns it */
    @ParameterizedTest
    @EnumSource(RangerAdminDatabase.Type.class)
    public void testMappingIsDeletedWhateverItsOwner(RangerAdminDatabase.Type type) {
        RangerAdminDatabase database = DATABASES.get(type);
        String name = uniqueName();
        String location = "hdfs://nn:8020/warehouse/t3";
        DefaultResourceMappingDiffDao dao = new DefaultResourceMappingDiffDao(database.newDataSource());
        dao.insertDiff(MetastoreEntityDiffFactory.createEntity(name, HiveEntityType.TABLE, location, "alice", 7L));

        dao.deleteDiffsFor(new ResourceMapping(name, location));

        assertEquals(0, database.jdbc().queryForObject(
            "SELECT COUNT(*) FROM x_resource_mapping_diff WHERE old_name = ?", Integer.class, name));
    }

    private static String uniqueName() {
        return "hive.db1.t_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** the column names come in the case of the database */
    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        throw new AssertionError("No column " + column + " in " + row);
    }

    private static List<String> columns(DataSource dataSource) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, "x_resource_mapping_diff", null)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ENGLISH));
                }
            }
        }
        return columns;
    }
}
